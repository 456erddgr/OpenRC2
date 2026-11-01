package com.rc2.etx.ui;

/** 页面需要向 Activity 借用的能力（弹窗、toast、通知刷新）。 */
public interface Host {

    void toast(String msg);

    /** 弹出一个文本输入框；numeric 为 true 时用数字键盘。 */
    void prompt(String title, String initial, boolean numeric, PromptCb cb);

    /** 设置变更后回调（用于持久化 / 刷新外框）。 */
    void onSettingsChanged();

    /** 请求重绘（后台任务结束时调用）。 */
    void invalidateUi();

    interface PromptCb {
        void onResult(String text);
    }
}
