/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client.bind;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.format.Format;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.BasicAuth;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.http.annotation.RequestBean;
import io.micronaut.http.client.bind.binders.AttributeClientRequestBinder;
import io.micronaut.http.client.bind.binders.HeaderClientRequestBinder;
import io.micronaut.http.client.bind.binders.VersionClientRequestBinder;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.uri.UriMatchVariable;
import jakarta.inject.Singleton;
import kotlin.coroutines.Continuation;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.micronaut.core.util.KotlinUtils.KOTLIN_COROUTINES_SUPPORTED;

/**
 * Default implementation of {@link HttpClientBinderRegistry} that searches by
 * annotation then by type.
 *
 * @author James Kleeh
 * @since 2.1.0
 */
@Singleton
@Internal
public class DefaultHttpClientBinderRegistry implements HttpClientBinderRegistry {

    private final Map<Class<? extends Annotation>, ClientArgumentRequestBinder<?>> byAnnotation = new LinkedHashMap<>();
    private final Map<Integer, ClientArgumentRequestBinder<?>> byType = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, AnnotatedClientRequestBinder<?>> methodByAnnotation = new LinkedHashMap<>();

    /**
     * @param conversionService The conversion service
     * @param binders           The request binders
     * @param beanContext       The context to resolve beans
     */
    protected DefaultHttpClientBinderRegistry(ConversionService<?> conversionService,
                                              List<ClientRequestBinder> binders,
                                              BeanContext beanContext) {
        byType.put(Argument.of(HttpHeaders.class).typeHashCode(), (ClientArgumentRequestBinder<HttpHeaders>) (context, uriContext, value, request) -> {
            value.forEachValue(request::header);
        });
        byType.put(Argument.of(Cookies.class).typeHashCode(), (ClientArgumentRequestBinder<Cookies>) (context, uriContext, value, request) -> {
            request.cookies(value.getAll());
        });
        byType.put(Argument.of(Cookie.class).typeHashCode(), (ClientArgumentRequestBinder<Cookie>) (context, uriContext, value, request) -> {
            request.cookie(value);
        });
        byType.put(Argument.of(BasicAuth.class).typeHashCode(), (ClientArgumentRequestBinder<BasicAuth>) (context, uriContext, value, request) -> {
            request.basicAuth(value.getUsername(), value.getPassword());
        });
        byType.put(Argument.of(Locale.class).typeHashCode(), (ClientArgumentRequestBinder<Locale>) (context, uriContext, value, request) -> {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, value.toLanguageTag());
        });
        byAnnotation.put(QueryValue.class, (context, uriContext, value, request) -> {
            String parameterName = context.getAnnotationMetadata().stringValue(QueryValue.class)
                    .filter (StringUtils::isNotEmpty)
                    .orElse(context.getArgument().getName());

            final UriMatchVariable uriVariable = uriContext.getUriTemplate().getVariables()
                    .stream()
                    .filter(v -> v.getName().equals(parameterName))
                    .findFirst()
                    .orElse(null);

            if (uriVariable != null) {
                if (uriVariable.isExploded()) {
                    uriContext.setPathParameter(parameterName, value);
                } else {
                    String convertedValue
                            = conversionService.convert(value, ConversionContext.STRING.with(context.getAnnotationMetadata()))
                            .filter(StringUtils::isNotEmpty)
                            .orElse(null);
                    if (convertedValue != null) {
                        uriContext.setPathParameter(parameterName, convertedValue);
                    } else {
                        uriContext.setPathParameter(parameterName, value);
                    }
                }
            } else {
                conversionService.convert(value, ConversionContext.STRING.with(context.getAnnotationMetadata()))
                        .filter(StringUtils::isNotEmpty)
                        .ifPresent(convertedValue -> uriContext.addQueryParameter(parameterName, convertedValue));
            }
        });

        byAnnotation.put(PathVariable.class, (context, uriContext, value, request) -> {
            String parameterName = context.getAnnotationMetadata().stringValue(PathVariable.class)
                    .filter (StringUtils::isNotEmpty)
                    .orElse(context.getArgument().getName());

            conversionService.convert(value, ConversionContext.STRING.with(context.getAnnotationMetadata()))
                    .filter(StringUtils::isNotEmpty)
                    .ifPresent(param -> uriContext.getPathParameters().put(parameterName, param));
        });
        byAnnotation.put(CookieValue.class, (context, uriContext, value, request) -> {
            String cookieName = context.getAnnotationMetadata().stringValue(CookieValue.class)
                    .filter(StringUtils::isNotEmpty)
                    .orElse(context.getArgument().getName());

            conversionService.convert(value, String.class)
                    .ifPresent(o -> request.cookie(Cookie.of(cookieName, o)));
        });
        byAnnotation.put(Header.class, (context, uriContext, value, request) -> {
            AnnotationMetadata annotationMetadata = context.getAnnotationMetadata();
            String headerName = annotationMetadata
                    .stringValue(Header.class)
                    .filter(StringUtils::isNotEmpty)
                    .orElse(NameUtils.hyphenate(context.getArgument().getName()));

            conversionService.convert(value, String.class)
                    .ifPresent(header -> request.getHeaders().set(headerName, header));
        });
        byAnnotation.put(RequestAttribute.class, (context, uriContext, value, request) -> {
            AnnotationMetadata annotationMetadata = context.getAnnotationMetadata();
            String attributeName = annotationMetadata
                    .stringValue(RequestAttribute.class)
                    .filter(StringUtils::isNotEmpty)
                    .orElse(NameUtils.hyphenate(context.getArgument().getName()));
            request.getAttributes().put(attributeName, value);

            conversionService.convert(value, String.class)
                    .ifPresent(v -> uriContext.getPathParameters().put(context.getArgument().getName(), v));
        });
        byAnnotation.put(Body.class, (context, uriContext, value, request) -> {
            request.body(value);
        });
        byAnnotation.put(RequestBean.class, (context, uriContext, value, request) -> {
            BeanIntrospection<Object> introspection = BeanIntrospection.getIntrospection(context.getArgument().getType());
            for (BeanProperty<Object, Object> beanProperty : introspection.getBeanProperties()) {
                findArgumentBinder(beanProperty.asArgument()).ifPresent(binder -> {
                    Object propertyValue = beanProperty.get(value);
                    if (propertyValue != null) {
                        ((ClientArgumentRequestBinder<Object>) binder).bind(context.with(beanProperty.asArgument()), uriContext, propertyValue, request);
                    }
                });
            }
        });

        methodByAnnotation.put(Header.class, new HeaderClientRequestBinder());
        methodByAnnotation.put(Version.class, new VersionClientRequestBinder(beanContext));
        methodByAnnotation.put(RequestAttribute.class, new AttributeClientRequestBinder());

        if (KOTLIN_COROUTINES_SUPPORTED) {
            //Clients should do nothing with the continuation
            byType.put(Argument.of(Continuation.class).typeHashCode(),  (context, uriContext, value, request) -> { });
        }

        if (CollectionUtils.isNotEmpty(binders)) {
            for (ClientRequestBinder binder: binders) {
                addBinder(binder);
            }
        }
    }

    @Override
    public <T> Optional<ClientArgumentRequestBinder<?>> findArgumentBinder(@NonNull Argument<T> argument) {
        Optional<Class<? extends Annotation>> opt = argument.getAnnotationMetadata().getAnnotationTypeByStereotype(Bindable.class);
        if (opt.isPresent()) {
            Class<? extends Annotation> annotationType = opt.get();
            ClientArgumentRequestBinder<?> binder = byAnnotation.get(annotationType);
            return Optional.ofNullable(binder);
        } else {
            Optional<ClientArgumentRequestBinder<?>> typeBinder = findTypeBinder(argument);
            if (typeBinder.isPresent()) {
                return typeBinder;
            }
            if (argument.isOptional()) {
                Argument<?> typeArgument = argument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
                return findTypeBinder(typeArgument);
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnnotatedClientRequestBinder<?>> findAnnotatedBinder(@NonNull Class<?> annotationType) {
        return Optional.ofNullable(methodByAnnotation.get(annotationType));
    }

    /**
     * Adds a binder to the registry.
     *
     * @param binder The binder
     * @param <T> The type
     */
    public <T> void addBinder(ClientRequestBinder binder) {
        if (binder instanceof AnnotatedClientRequestBinder) {
            AnnotatedClientRequestBinder<?> annotatedBinder = (AnnotatedClientRequestBinder<?>) binder;
            methodByAnnotation.put(annotatedBinder.getAnnotationType(), annotatedBinder);
        } else if (binder instanceof AnnotatedClientArgumentRequestBinder) {
            AnnotatedClientArgumentRequestBinder<?> annotatedRequestArgumentBinder = (AnnotatedClientArgumentRequestBinder<?>) binder;
            Class<? extends Annotation> annotationType = annotatedRequestArgumentBinder.getAnnotationType();
            byAnnotation.put(annotationType, annotatedRequestArgumentBinder);
        } else if (binder instanceof TypedClientArgumentRequestBinder) {
            TypedClientArgumentRequestBinder<?> typedRequestArgumentBinder = (TypedClientArgumentRequestBinder<?>) binder;
            byType.put(typedRequestArgumentBinder.argumentType().typeHashCode(), typedRequestArgumentBinder);
            List<Class<?>> superTypes = typedRequestArgumentBinder.superTypes();
            if (CollectionUtils.isNotEmpty(superTypes)) {
                for (Class<?> superType : superTypes) {
                    byType.put(Argument.of(superType).typeHashCode(), typedRequestArgumentBinder);
                }
            }
        }
    }

    private <T> Optional<ClientArgumentRequestBinder<?>> findTypeBinder(Argument<T> argument) {
        ClientArgumentRequestBinder<?> binder = byType.get(argument.typeHashCode());
        if (binder != null) {
            return Optional.of(binder);
        }
        return Optional.ofNullable(byType.get(Argument.of(argument.getType()).typeHashCode()));
    }
}
