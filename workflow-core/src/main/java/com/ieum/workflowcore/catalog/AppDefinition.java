package com.ieum.workflowcore.catalog;

/** apps.json 항목. 내장 앱(webhook·schedule·manual·http·branch·transform·approval·ai)은 serviceType·auth가 null. */
public record AppDefinition(String key, String provider, String title, String brand, String serviceType, String auth) {
}
