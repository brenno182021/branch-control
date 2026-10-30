package br.com.el.branchcontrol;

import java.util.List;

public final class UserModels {
    private UserModels() {}
    public record Account(String id, String username, String name, String passwordHash, String group,
                          boolean enabled, long revision, String createdAt, String updatedAt) {
        public UserView view() { return new UserView(id, username, name, group, enabled, createdAt, updatedAt, Groups.ADM.equals(group)); }
        public Models.Actor actor() { return new Models.Actor(id, username, group); }
    }
    public record UserView(String id, String username, String name, String group, boolean enabled,
                           String createdAt, String updatedAt, boolean administrator) {}
    public record UsersFile(int version, List<Account> users) {}
    public record SessionView(boolean authenticated, UserView user, String csrfToken, boolean setupRequired,
                              boolean setupAllowed) {}
}
