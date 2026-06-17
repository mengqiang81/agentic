package com.example.agentic.agent;

/**
 * AG-UI 协议事件 DTO。
 */
public class AgUiEvent {

    private final String type;
    private final String data;

    public AgUiEvent(String type, String data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public String getData() {
        return data;
    }
}
