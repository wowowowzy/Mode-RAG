package com.challenger.moderoute.service;

import com.challenger.moderoute.dto.EnhanceRequest;
import com.challenger.moderoute.dto.EnhanceResponse;


public interface EnhanceService {

    EnhanceResponse enhance(EnhanceRequest request);
}
