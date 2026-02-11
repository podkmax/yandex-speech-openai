package com.example.ttsserver.service;

public interface TokenProvider {

    String getToken();

    void forceRefresh();
}
