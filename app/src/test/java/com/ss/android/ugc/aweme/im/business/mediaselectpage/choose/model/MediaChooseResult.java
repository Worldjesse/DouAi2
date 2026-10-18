package com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.model;

import com.ss.android.ugc.aweme.im.service.model.MediaModel;
import java.io.Serializable;
import java.util.ArrayList;

public final class MediaChooseResult implements Serializable {
    private final ArrayList<MediaModel> selected;
    public MediaChooseResult(ArrayList<MediaModel> selected) { this.selected = selected; }
    public ArrayList<MediaModel> getSelectedList() { return selected; }
}
