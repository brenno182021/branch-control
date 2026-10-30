package br.com.el.branchcontrol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentPolicyTest {
    @Test void masksMatchOnlyAuthorizedNamesIncludingRemoteCanonicalRefs() {
        var env=new EnvironmentStore.Environment("id","Restrito","repo",List.of("chore/rhf/*","draft/rhf/*","release/2026-??-??"),List.of(),List.of(),true,1);
        for(String branch:List.of("refs/heads/chore/rhf/uma-tarefa","refs/heads/draft/rhf/time","refs/remotes/origin/draft/rhf/time","release/2026-10-30")) assertTrue(EnvironmentStore.allows(env,branch),branch);
        for(String branch:List.of("refs/heads/main","refs/remotes/origin/draft/fin/time","chore/rhf-fora","draft/rhf","release/2026-1-1")) assertFalse(EnvironmentStore.allows(env,branch),branch);
        assertTrue(EnvironmentStore.matches("feature/v1.0","feature/v1.0"));
        assertFalse(EnvironmentStore.matches("feature/v1.0","feature/v1X0"));
    }
    @Test void validatesMasksAndDoesNotTreatPatternsAsRegex() {
        assertThrows(AppException.class,()->EnvironmentStore.validatePattern("refs/heads/draft/*"));
        assertThrows(AppException.class,()->EnvironmentStore.validatePattern("draft/rhf/*\nmain"));
        assertThrows(AppException.class,()->EnvironmentStore.validatePattern(""));
        assertDoesNotThrow(()->EnvironmentStore.validatePattern("*"));
        assertFalse(EnvironmentStore.matches("(main|secret)","secret"));
    }
}
