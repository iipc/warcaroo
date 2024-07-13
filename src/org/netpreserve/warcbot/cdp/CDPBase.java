package org.netpreserve.warcbot.cdp;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class CDPBase {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T domain(Class<T> domainInterface) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{domainInterface},
                (proxy, method, args) -> {
                    var methodParameters = method.getParameters();
                    if (method.getName().startsWith("on") && methodParameters.length == 1) {
                        var type = ((ParameterizedType)method.getGenericParameterTypes()[0]);
                        var eventClass = (Class<?>)type.getActualTypeArguments()[0];
                        addListener(eventClass, (Consumer)args[0]);
                        return null;
                    }
                    var params = new HashMap<String, Object>(methodParameters.length);
                    for (int i = 0; i < methodParameters.length; i++) {
                        if (args[i] != null) params.put(methodParameters[i].getName(), args[i]);
                    }
                    return send(domainInterface.getSimpleName() + "." + method.getName(), params,
                            method.getGenericReturnType());
                });
    }

    public abstract <T> void addListener(Class<T> eventClass, Consumer<T> callback);

    protected abstract <T> T send(String method, Map<String, Object> params, Type returnType);
}
