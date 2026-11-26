package com.odo.common.storage;

/** 저장소에 있는 객체의 크기(바이트)와 형식(Content-Type). */
public record StoredObject(long size, String contentType) {

}
