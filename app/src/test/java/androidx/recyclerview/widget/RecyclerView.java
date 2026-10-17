package androidx.recyclerview.widget;

import android.content.Context;
import android.widget.FrameLayout;

/** Host-owned view fixture; the production adapter deliberately uses reflection. */
public class RecyclerView extends FrameLayout {
    private Object layoutManager;
    public RecyclerView(Context context) { super(context); }
    public Object getLayoutManager() { return layoutManager; }
    public void setLayoutManager(Object value) { layoutManager = value; }
}
