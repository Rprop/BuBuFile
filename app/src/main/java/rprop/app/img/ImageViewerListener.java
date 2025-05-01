package rprop.app.img;

public interface ImageViewerListener {
    void onBack();

    void onShareRequested();

    void onCopyToRequested();

    void onImageLoadFailed();
}
