package com.majstr.backend.exception;

/**
 * The admin asked to delete their own account or another admin's — 409
 * {@code ADMIN_USER_DELETE_REFUSED}. Nothing is touched.
 */
public class AdminUserDeleteRefusedException extends RuntimeException {

    public AdminUserDeleteRefusedException() {
        super("error.admin.user-delete-refused");
    }
}
