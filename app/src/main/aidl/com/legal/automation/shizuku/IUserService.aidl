package com.legal.automation.shizuku;

interface IUserService {
    // Reserved by Shizuku for lifecycle teardown.
    void destroy() = 16777114;

    // Runs a shell command with the privileges Shizuku was granted (adb/shell
    // or root) and returns combined stdout+stderr. Used for low-level actions
    // the AccessibilityService cannot do reliably: launching apps, typing text,
    // and coordinate taps as a fallback.
    String exec(String command) = 1;
}
