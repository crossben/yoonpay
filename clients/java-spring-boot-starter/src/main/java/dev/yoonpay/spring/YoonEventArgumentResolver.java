package dev.yoonpay.spring;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the verified {@link YoonEvent} into a controller parameter of that type. */
public class YoonEventArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return YoonEvent.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Object event = request.getAttribute(YoonWebhookFilter.EVENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (event == null) {
            throw new IllegalStateException("No verified Yoon event: is this path listed in yoon.webhook.paths?");
        }
        return event;
    }
}
