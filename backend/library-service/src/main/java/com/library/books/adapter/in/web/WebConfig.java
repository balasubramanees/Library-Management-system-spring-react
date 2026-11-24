package com.library.books.adapter.in.web;

import com.library.books.application.port.MemberDirectory;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MemberDirectory directory;

    public WebConfig(MemberDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentMemberResolver(directory));
    }
}
