package cn.zgccrui.passwordswitch;
import android.os.Bundle;
interface ISettingsService {
    Bundle read(int userId) = 1;
    Bundle apply(int userId, String credential, String autofill, boolean keepOthers, in Bundle expected) = 2;
    Bundle restore(int userId, in Bundle target, in Bundle expected) = 3;
    void destroy() = 16777114;
}
