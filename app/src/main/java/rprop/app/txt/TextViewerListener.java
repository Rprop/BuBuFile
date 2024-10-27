package rprop.app.txt;

public interface TextViewerListener {
    void onBack();

    void onLoadMore();

    void onShareRequested();

    boolean hasMoreText();
}
