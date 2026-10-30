package br.com.el.branchcontrol;
import java.util.List;
/** Global account roles. Environment names and permissions are configured by ADM. */
public final class Groups {
    public static final String ADM="ADM", USER="USER";
    public static final List<String> ALL=List.of(USER,ADM);
    public static void require(String value) {
        if (!ALL.contains(value)) throw AppException.bad("Papel inválido. Escolha ADM ou USER; os ambientes são cadastrados separadamente.");
    }
    private Groups() {}
}
