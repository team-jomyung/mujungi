package kr.mujoong.core.result;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.startup.CoreStartupSequence;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
/** BR1.2: REPORT와 SESSION_RESULT는 각각 전용 실행 스레드를 가진다. */
@Component
public class JobRunnerLoop implements SmartLifecycle {
    private final JobRunner runner;
    private final long tickMillis;
    private final Map<String,ExecutorService> workers=new LinkedHashMap<>();
    private final Map<String,AtomicBoolean> busy=new ConcurrentHashMap<>();
    private final Map<UUID,Future<?>> running=new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private final kr.mujoong.core.observability.EventLogger events;
    public JobRunnerLoop(JobRunner runner,CoreProperties properties,kr.mujoong.core.observability.EventLogger events){this.runner=runner;this.tickMillis=properties.timing().result().jobTick().toMillis();this.events=events;}
    @Override public synchronized void start(){
        if(scheduler!=null)return;
        runner.startup();
        for(String type:List.of("REPORT","SESSION_RESULT")){
            busy.put(type,new AtomicBoolean());workers.put(type,Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("result-"+type).factory()));
        }
        scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("result-tick").factory());
        scheduler.scheduleWithFixedDelay(this::tick,0,tickMillis,TimeUnit.MILLISECONDS);
    }
    void tick(){
        try{
            for(UUID id:runner.expire()){var future=running.get(id);if(future!=null)future.cancel(true);}
            for(String type:List.of("REPORT","SESSION_RESULT"))if(busy.get(type).compareAndSet(false,true)){
                var slot=busy.get(type);
                List<ResultJob> claimed;
                try{claimed=runner.claim(type);}catch(RuntimeException error){slot.set(false);throw error;}
                if(claimed.isEmpty()){slot.set(false);continue;}
                var job=claimed.getFirst();
                FutureTask<Void> task=new FutureTask<>(()->{try{runner.execute(job);}finally{slot.set(false);running.remove(job.id());}return null;});
                running.put(job.id(),task);workers.get(type).execute(task);
            }
        }catch(RuntimeException error){events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ResultTickFailed");}
    }
    @Override public synchronized void stop(){
        if(scheduler==null)return;
        scheduler.shutdownNow();scheduler=null;running.values().forEach(f->f.cancel(true));
        workers.values().forEach(ExecutorService::shutdownNow);workers.clear();running.clear();busy.clear();
    }
    @Override public synchronized boolean isRunning(){return scheduler!=null;}
    @Override public int getPhase(){return CoreStartupSequence.PHASE+1;}
}
