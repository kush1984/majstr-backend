package com.majstr.backend.repository;

import java.util.UUID;

/** Per owner: how many estimates (ADDENDUMs excluded) and the line count of the fullest one. */
public interface OwnerEstimateFill {
    UUID getOwnerId();
    long getEstimates();
    long getMaxLines();
}
