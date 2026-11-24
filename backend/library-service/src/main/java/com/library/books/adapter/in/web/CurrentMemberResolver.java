package com.library.books.adapter.in.web;

import com.library.books.application.port.MemberDirectory;
import com.library.books.domain.Member;
import com.library.books.domain.UnauthenticatedException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the authenticated {@link Member} into any controller parameter of that type, by
 * introspecting the bearer token with user-service. Controllers therefore cannot forget to
 * authenticate: if they need a member, they get one or the request fails with 401.
 */
public class CurrentMemberResolver implements HandlerMethodArgumentResolver {

    private static final String PREFIX = "Bearer ";

    private final MemberDirectory directory;

    public CurrentMemberResolver(MemberDirectory directory) {
        this.directory = directory;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Member.class.equals(parameter.getParameterType());
    }

    @Override
    public Member resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new UnauthenticatedException();
        }
        String token = header.substring(PREFIX.length()).strip();
        if (token.isEmpty()) {
            throw new UnauthenticatedException();
        }
        return directory.findByToken(token).orElseThrow(UnauthenticatedException::new);
    }
}
