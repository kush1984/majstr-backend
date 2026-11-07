package com.majstr.backend.exception;

/**
 * A profile save that would erase a name already given (V190 review). The name is optional only until
 * it is first given: by then documents may have left with it, and a client signing on the portal after
 * it was erased would freeze a document with no contractor. Maps to 400 {@code PROFILE_NAME_REQUIRED}.
 */
public class ProfileNameRequiredException extends RuntimeException {
    public ProfileNameRequiredException() {
        super("error.profile.name-required");
    }
}
