package br.com.el.branchcontrol;
import java.util.Set;
public final class Permissions {
    public static final String REPORTS="REPORTS", PREPARE="PREPARE", SIMULATE="SIMULATE", APPLY="APPLY", FETCH="FETCH", SWITCH="SWITCH";
    public static final Set<String> ALL=Set.of(REPORTS,PREPARE,SIMULATE,APPLY,FETCH,SWITCH);
    private Permissions() {}
}
