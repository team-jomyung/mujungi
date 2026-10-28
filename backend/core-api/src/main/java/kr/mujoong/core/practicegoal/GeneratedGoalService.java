package kr.mujoong.core.practicegoal;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.practice.GoalSelection;
import kr.mujoong.core.practice.PracticeAccountPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Guardian-serialized writes pin the observed handle, never the current candidate version. */
@Service
public class GeneratedGoalService {
    private final GoalCandidateRepository repository;
    private final PracticeAccountPort accounts;
    private final ConsentPolicyService consent;
    private final MujoongClock clock;
    private final TransactionTemplate tx;
    public GeneratedGoalService(GoalCandidateRepository repository,PracticeAccountPort accounts,ConsentPolicyService consent,
            MujoongClock clock,PlatformTransactionManager transactions){this.repository=repository;this.accounts=accounts;this.consent=consent;this.clock=clock;tx=new TransactionTemplate(transactions);}
    public Optional<GoalSelection> add(UUID guardian,UUID handle){
        var current=accounts.requireActive(guardian,false);
        if(repository.version(current.childId(),handle).isEmpty())return Optional.empty();
        return write(guardian,owner->{
        var version=repository.version(owner.childId(),handle);if(version.isEmpty())return Optional.empty();
        if(!repository.sourceIsCurrent(version.get()))throw CoreApiException.of(ErrorCode.NOT_FOUND);
        UUID id=repository.select(guardian,version.get(),clock.now());return repository.selection(guardian,id);
    });}
    public Optional<GoalSelection> find(UUID guardian,UUID id){
        var selection=repository.selection(guardian,id);if(selection.isEmpty())return Optional.empty();
        var owner=accounts.requireActive(guardian,false);requireConsent(guardian);
        return selection.filter(s->owner.childId().equals(s.childId()));
    }
    public List<GoalSelection> list(UUID guardian){var owner=accounts.requireActive(guardian,false);requireConsent(guardian);return repository.list(guardian).stream().filter(s->owner.childId().equals(s.childId())).toList();}
    public GoalSelection favorite(UUID guardian,UUID id,boolean favorite){return write(guardian,o->{var s=owned(guardian,id);if(!s.active())missing();if(favorite&&!s.isFavorite())repository.requireFavoriteCapacity(guardian);repository.favorite(id,favorite);return owned(guardian,id);});}
    public Instant delete(UUID guardian,UUID id,java.time.Duration window){return write(guardian,o->{var s=owned(guardian,id);if(!s.active())return s.restoreUntil();Instant now=clock.now(),until=now.plus(window);repository.delete(id,now,until);return until;});}
    public GoalSelection restore(UUID guardian,UUID id){return write(guardian,o->{
        var s=owned(guardian,id);if(s.active())return s;
        if(!clock.now().isBefore(s.restoreUntil()))throw CoreApiException.of(ErrorCode.RESTORE_EXPIRED);
        var v=repository.version(o.childId(),s.recommendationId()).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        if(repository.activeSelection(o.childId(),v.candidateId()).isPresent())throw CoreApiException.of(ErrorCode.ALREADY_SELECTED);
        if(s.isFavorite())repository.requireFavoriteCapacity(guardian);
        repository.restore(id);return owned(guardian,id);
    });}
    private GoalSelection owned(UUID guardian,UUID id){return repository.selection(guardian,id).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));}
    private void requireConsent(UUID guardian){if(!consent.check(guardian).ok())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);}
    private <T>T write(UUID guardian,Function<PracticeAccountPort.AccountContext,T> operation){return tx.execute(t->{var owner=accounts.requireActive(guardian,true);requireConsent(guardian);return operation.apply(owner);});}
    private static void missing(){throw CoreApiException.of(ErrorCode.NOT_FOUND);}
}
