package com.cmcc.cmdevops.security.federation.ask;

/**
 * 问数过程事件，事件名见 {@link AskService}。
 */
@FunctionalInterface
public interface AskListener {

    void emit(String event, Object data);
}
