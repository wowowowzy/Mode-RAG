package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.CardLibrary;

import com.challenger.moderoute.entity.CardLibrary;

public interface CardLibraryService {

    CardLibrary library();

    String embeddingModelName();

    String vectorStoreKind();

    boolean vectorsReady();

    /** 首次请求或预热线程调用；失败抛出，由调用方降级。 */
    CardLibrary ensureVectors();
}
