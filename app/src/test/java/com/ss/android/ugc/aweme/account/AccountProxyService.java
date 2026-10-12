package com.ss.android.ugc.aweme.account;

/** Host contract fixture; this class is never packaged in the module. */
public final class AccountProxyService {
    private static final UserService USER = new UserService();
    public static UserService userService() { return USER; }
    public static final class UserService {
        public boolean isLogin() { return true; }
        public String getCurUserId() { return "100"; }
    }
}
