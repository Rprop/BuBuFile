package rprop.app.txt;

public interface TextViewerListener {
    void onBack();

    void onLoadMore();

    void onShareRequested();

    void onCopyToRequested();

    boolean hasMoreText();
}
