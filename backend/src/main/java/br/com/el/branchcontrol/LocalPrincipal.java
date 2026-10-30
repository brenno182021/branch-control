package br.com.el.branchcontrol;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public final class LocalPrincipal implements UserDetails, CredentialsContainer {
    private static final long serialVersionUID = 1L;
    private final UserModels.UserView user;
    private final long revision;
    private transient String password;

    public LocalPrincipal(UserModels.Account account) {
        user = account.view(); revision = account.revision(); password = account.passwordHash();
    }
    public UserModels.UserView user() { return user; }
    public long revision() { return revision; }
    public Models.Actor actor() { return new Models.Actor(user.id(), user.username(), user.group()); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority(user.group())); }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return user.username(); }
    @Override public boolean isEnabled() { return user.enabled(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public void eraseCredentials() { password = null; }
}
