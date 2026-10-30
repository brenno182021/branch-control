package br.com.el.branchcontrol;
import java.util.Set;
public final class Permissions {
    public static final String APPROVE_USERS="APPROVE_USERS", REPORTS="REPORTS", PREPARE="PREPARE", SIMULATE="SIMULATE", APPLY="APPLY", FETCH="FETCH", SWITCH="SWITCH";
    public static final Set<String> ALL=Set.of(APPROVE_USERS,REPORTS,PREPARE,SIMULATE,APPLY,FETCH,SWITCH);
    public static final Set<String> GIT_OPERATIONS=Set.of(PREPARE,SIMULATE,APPLY,FETCH,SWITCH);
    private Permissions() {}
}
