package android.content;

public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 131072;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 536870912;

    public Intent() { }
    public Intent(String action, android.net.Uri data) { }
    public Intent(android.content.Context context, Class<?> target) { }
    public Intent addFlags(int flags) { return this; }
    public String packageName;
    public String className;

    public Intent setClassName(String packageName, String className) {
        this.packageName = packageName;
        this.className = className;
        return this;
    }
}
