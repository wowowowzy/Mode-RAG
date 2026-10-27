package com.challenger.moderoute.service;

import java.util.Map;

public interface AuditService {

    void record(Map<String, Object> event);
}
