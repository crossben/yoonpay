package dev.yoonpay.server.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers take an {@link AppPrincipal} parameter: the authenticated application. */
public class CurrentApp implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AppPrincipal.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object app = request.getAttribute(ApiKeyFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (app == null) {
            throw new IllegalStateException("No authenticated application on this request");
        }
        return app;
    }
}
