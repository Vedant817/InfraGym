package com.archforge.design.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Gives every request a named principal when authentication is switched off.
 *
 * <p><b>Why this exists rather than a null check.</b> With
 * {@code archforge.security.enabled=false} the filter chain permits everything and no
 * {@code Authentication} is populated, so {@code authentication.getName()} in a controller
 * was null and every design endpoint answered 500. The tempting fix is to make the controllers
 * null-tolerant. That is exactly wrong: this service's ownership checks live in the repository
 * signatures ({@code findByIdAndUserId}, {@code findByUserIdOrderByUpdatedAtDesc}), precisely
 * so that "did I remember to check?" cannot be expressed. A null-tolerant {@code getName()}
 * that falls back to "no filter" turns that structural guarantee back off by default, and the
 * fallback path is the one every local developer runs.
 *
 * <p>So the principal always exists and always has a name. The ownership check stays real --
 * it is still filtering by a user id -- it just resolves to the same local user for every
 * request when authentication is off. A design created locally is still only readable by that
 * user, and the code path taken is the same one production takes.
 *
 * <p>Named from configuration rather than hard-coded so a developer's local rows are
 * attributable, and so two developers sharing a database do not silently overwrite each
 * other's designs.
 */
public class LocalDevelopmentPrincipalFilter extends OncePerRequestFilter {

    private final String localUserId;

    public LocalDevelopmentPrincipalFilter(String localUserId) {
        this.localUserId = localUserId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            localUserId,
                            null,
                            // UntypedList, not List.of: AuthorityUtils returns a
                            // List<GrantedAuthority> whose element type javac cannot reconcile
                            // with the Collection<? extends GrantedAuthority> the token wants
                            // when the only argument is itself an inference variable.
                            Collections.unmodifiableList(
                                    AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_LOCAL"))));
        }
        chain.doFilter(request, response);
    }
}