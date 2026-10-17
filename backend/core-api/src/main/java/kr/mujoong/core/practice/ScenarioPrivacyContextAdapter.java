package kr.mujoong.core.practice;

import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import org.springframework.stereotype.Component;

/** 프로필 소유 경계를 통해서만 별칭을 얻는다. 실패를 빈 표로 대체하지 않는다. */
@Component
public final class ScenarioPrivacyContextAdapter implements ScenarioPrivacyContextPort {
    private final PracticeAccountPort accounts;
    private final ConsentPolicyService consent;
    private final ChildProfileService profiles;
    public ScenarioPrivacyContextAdapter(PracticeAccountPort accounts,ConsentPolicyService consent,ChildProfileService profiles){this.accounts=accounts;this.consent=consent;this.profiles=profiles;}
    @Override public Context require(UUID guardian,UUID child){
        var account=accounts.requireActive(guardian,false);
        if(!account.childId().equals(child))throw CoreApiException.of(ErrorCode.NOT_FOUND);
        if(!consent.check(guardian).ok())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        var profile=profiles.find(guardian).orElseThrow(ScenarioDeadline::failed);
        if(!profile.childId().equals(child))throw CoreApiException.of(ErrorCode.NOT_FOUND);
        return new Context(profile.displayName());
    }
}
