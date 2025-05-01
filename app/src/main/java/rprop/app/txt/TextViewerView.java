package rprop.app.txt;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import rp.file.R;
import rprop.app.ui.ThemeHelper;

public final class TextViewerView extends RelativeLayout {
    private static final int LOAD_MORE_THRESHOLD_PX = 200;

    private final ScrollView mVerticalScroll;
    private final HorizontalScrollView mHorizontalScroll;
    private final LinearLayout mContentRow;
    private final FrameLayout mWrapContainer;
    private final TextView mLineNumberView;
    private final TextView mContentView;
    private final MaterialToolbar mToolbar;
    private TextViewerListener mListener;
    private boolean mLoadMoreEnabled;
    private boolean mWordWrapEnabled;
    private String mSearchQuery = "";
    private final List<Integer> mSearchMatches = new ArrayList<>();
    private int mCurrentMatchIndex = -1;

    public TextViewerView(final Context context) {
        this(context, null);
    }

    public TextViewerView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextViewerView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.text_viewer_view, this, true);
        this.mVerticalScroll = findViewById(R.id.vertical_scroll);
        this.mHorizontalScroll = findViewById(R.id.horizontal_scroll);
        this.mContentRow = findViewById(R.id.content_row);
        this.mWrapContainer = findViewById(R.id.wrap_container);
        this.mLineNumberView = findViewById(R.id.line_number_view);
        this.mContentView = findViewById(R.id.content_view);
        disableEmojiCompat(this.mLineNumberView);
        disableEmojiCompat(this.mContentView);
        this.mToolbar = findViewById(R.id.toolbar);
        final Drawable overflowIcon = AppCompatResources.getDrawable(context, R.drawable.ic_more_vert);
        if (overflowIcon != null) {
            final Drawable tintedIcon = DrawableCompat.wrap(overflowIcon.mutate());
            DrawableCompat.setTint(tintedIcon, MaterialColors.getColor(
                    context, com.google.android.material.R.attr.colorOnSurface, 0));
            this.mToolbar.setOverflowIcon(tintedIcon);
        }
        this.mToolbar.setNavigationOnClickListener(v -> {
            if (this.mListener != null) {
                this.mListener.onBack();
            }
        });
        this.mToolbar.setOnMenuItemClickListener(item -> {
            final int itemId = item.getItemId();
            if (itemId == R.id.action_find_previous) {
                performSearch(this.mSearchQuery, false, null);
                return true;
            }
            if (itemId == R.id.action_find_next) {
                performSearch(this.mSearchQuery, true, null);
                return true;
            }
            if (itemId == R.id.action_word_wrap) {
                setWordWrapEnabled(!this.mWordWrapEnabled);
                item.setChecked(this.mWordWrapEnabled);
                return true;
            }
            if (itemId == R.id.action_share) {
                if (this.mListener != null) {
                    this.mListener.onShareRequested();
                }
                return true;
            }
            if (itemId == R.id.action_copy_to) {
                if (this.mListener != null) {
                    this.mListener.onCopyToRequested();
                }
                return true;
            }
            if (itemId == R.id.action_find) {
                showFindDialog();
                return true;
            }
            return false;
        });
        this.mVerticalScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!this.mLoadMoreEnabled || scrollY <= oldScrollY) {
                return;
            }
            final int contentHeight = this.mVerticalScroll.getChildAt(0).getHeight();
            if (scrollY + this.mVerticalScroll.getHeight() >= contentHeight - LOAD_MORE_THRESHOLD_PX
                    && this.mListener != null) {
                this.mListener.onLoadMore();
            }
        });
    }

    private void setWordWrapEnabled(final boolean enabled) {
        if (this.mWordWrapEnabled == enabled) {
            return;
        }
        this.mWordWrapEnabled = enabled;
        final ViewGroup currentParent = (ViewGroup) this.mContentView.getParent();
        if (currentParent != null) {
            currentParent.removeView(this.mContentView);
        }
        if (enabled) {
            this.mHorizontalScroll.setVisibility(View.GONE);
            this.mWrapContainer.setVisibility(View.VISIBLE);
            this.mContentView.setHorizontallyScrolling(false);
            this.mContentView.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
            this.mWrapContainer.addView(this.mContentView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            this.mWrapContainer.setVisibility(View.GONE);
            this.mHorizontalScroll.setVisibility(View.VISIBLE);
            this.mContentView.setHorizontallyScrolling(true);
            this.mContentRow.addView(this.mContentView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            this.mHorizontalScroll.scrollTo(0, 0);
        }
        this.mContentView.requestLayout();
    }

    public void setOnTextViewerListener(final TextViewerListener listener) {
        this.mListener = listener;
    }

    public void setLoadMoreEnabled(final boolean enabled) {
        this.mLoadMoreEnabled = enabled;
    }

    public void setText(final CharSequence text) {
        final String content = text != null ? text.toString() : "";
        final int lineCount = countLines(content);
        updateLineNumberWidth(lineCount);
        this.mLineNumberView.setText(buildLineNumbers(1, lineCount), TextView.BufferType.NORMAL);
        this.mContentView.setText(content, TextView.BufferType.NORMAL);
        refreshSearchIfActive();
    }

    public void appendText(@NonNull final CharSequence text) {
        final String chunk = text.toString();
        if (chunk.isEmpty()) {
            return;
        }
        final String current = this.mContentView.getText().toString();
        final int oldLineCount = countLines(current);
        final String newContent = current + chunk;
        final int totalLineCount = countLines(newContent);
        this.mContentView.setText(newContent, TextView.BufferType.NORMAL);
        if (totalLineCount > oldLineCount) {
            appendLineNumbers(oldLineCount + 1, totalLineCount);
        }
        updateLineNumberWidth(totalLineCount);
        refreshSearchIfActive();
    }

    public void requestLoadMoreIfNeeded() {
        post(() -> {
            if (!this.mLoadMoreEnabled || this.mListener == null) {
                return;
            }
            if (this.mVerticalScroll.getChildCount() == 0) {
                return;
            }
            if (this.mVerticalScroll.getChildAt(0).getHeight() <= this.mVerticalScroll.getHeight()) {
                this.mListener.onLoadMore();
            }
        });
    }

    private void appendLineNumbers(final int fromLine, final int toLine) {
        final StringBuilder builder = new StringBuilder(this.mLineNumberView.getText());
        for (int line = fromLine; line <= toLine; line++) {
            builder.append('\n');
            builder.append(line);
        }
        this.mLineNumberView.setText(builder.toString(), TextView.BufferType.NORMAL);
    }

    private static void disableEmojiCompat(final TextView textView) {
        if (textView instanceof AppCompatTextView) {
            ((AppCompatTextView) textView).setEmojiCompatEnabled(false);
        }
    }

    private void updateLineNumberWidth(final int lineCount) {
        this.mLineNumberView.setMinWidth(measureLineNumberWidth(lineCount));
    }

    private int measureLineNumberWidth(final int lineCount) {
        final int digits = lineCount > 0 ? String.valueOf(lineCount).length() : 1;
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f + digits * 8f,
                getResources().getDisplayMetrics());
    }

    private static int countLines(final String text) {
        if (text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String buildLineNumbers(final int fromLine, final int toLine) {
        final StringBuilder builder = new StringBuilder(Math.max((toLine - fromLine + 1) * 2, 2));
        for (int line = fromLine; line <= toLine; line++) {
            if (line > fromLine) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private void showFindDialog() {
        final Context context = getContext();
        final View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_text_find, null);
        final TextInputEditText queryInput = dialogView.findViewById(R.id.find_query);
        final TextView statusView = dialogView.findViewById(R.id.find_status);
        final String initialQuery = !this.mSearchQuery.isEmpty()
                ? this.mSearchQuery
                : ThemeHelper.getTextFindQuery(context);
        if (!initialQuery.isEmpty()) {
            queryInput.setText(initialQuery);
            queryInput.setSelection(initialQuery.length());
        }
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                performSearch(queryInput.getText().toString(), true, statusView);
                return true;
            }
            return false;
        });
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.find_in_text)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> resetSearch())
                .setPositiveButton(R.string.find_in_text, (dialog, which) ->
                        performSearch(queryInput.getText().toString(), true, statusView))
                .show();
    }

    private void performSearch(@Nullable final String query, final boolean forward,
                               @Nullable final TextView statusView) {
        if (query == null || query.isEmpty()) {
            resetSearch();
            if (statusView != null) {
                statusView.setVisibility(View.GONE);
            }
            return;
        }
        if (!query.equals(this.mSearchQuery)) {
            this.mSearchQuery = query;
            ThemeHelper.setTextFindQuery(getContext(), query);
            collectSearchMatches();
            this.mCurrentMatchIndex = -1;
        }
        updateFindToolbarActions();
        if (this.mSearchMatches.isEmpty()) {
            clearSearchHighlight();
            if (statusView != null) {
                statusView.setVisibility(View.VISIBLE);
                if (this.mListener != null && this.mListener.hasMoreText()) {
                    statusView.setText(R.string.find_not_found_more);
                } else {
                    statusView.setText(R.string.find_not_found);
                }
            }
            return;
        }
        if (forward) {
            if (this.mCurrentMatchIndex < 0) {
                this.mCurrentMatchIndex = 0;
            } else {
                this.mCurrentMatchIndex = (this.mCurrentMatchIndex + 1) % this.mSearchMatches.size();
            }
        } else if (this.mCurrentMatchIndex < 0) {
            this.mCurrentMatchIndex = this.mSearchMatches.size() - 1;
        } else {
            this.mCurrentMatchIndex = (this.mCurrentMatchIndex - 1 + this.mSearchMatches.size())
                    % this.mSearchMatches.size();
        }
        applySearchHighlight();
        scrollToMatch(this.mSearchMatches.get(this.mCurrentMatchIndex));
        if (statusView != null) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(getResources().getString(
                    R.string.find_result,
                    this.mCurrentMatchIndex + 1,
                    this.mSearchMatches.size()));
        }
    }

    private void updateFindToolbarActions() {
        final boolean active = !this.mSearchQuery.isEmpty();
        this.mToolbar.getMenu().findItem(R.id.action_find_previous).setVisible(active);
        this.mToolbar.getMenu().findItem(R.id.action_find_next).setVisible(active);
    }

    private void collectSearchMatches() {
        this.mSearchMatches.clear();
        final String text = this.mContentView.getText().toString();
        int index = 0;
        while ((index = text.indexOf(this.mSearchQuery, index)) >= 0) {
            this.mSearchMatches.add(index);
            index += this.mSearchQuery.length();
        }
    }

    private void refreshSearchIfActive() {
        if (this.mSearchQuery.isEmpty()) {
            return;
        }
        final int previousStart = this.mCurrentMatchIndex >= 0
                && this.mCurrentMatchIndex < this.mSearchMatches.size()
                ? this.mSearchMatches.get(this.mCurrentMatchIndex) : 0;
        collectSearchMatches();
        this.mCurrentMatchIndex = findNearestMatchIndex(previousStart);
        if (this.mSearchMatches.isEmpty()) {
            clearSearchHighlight();
            return;
        }
        applySearchHighlight();
    }

    private int findNearestMatchIndex(final int targetStart) {
        int nearestIndex = 0;
        int nearestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < this.mSearchMatches.size(); i++) {
            final int distance = Math.abs(this.mSearchMatches.get(i) - targetStart);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private void applySearchHighlight() {
        final String text = this.mContentView.getText().toString();
        final SpannableString spannable = new SpannableString(text);
        final int highlightColor = ContextCompat.getColor(getContext(), R.color.text_search_highlight);
        final int currentColor = ContextCompat.getColor(getContext(), R.color.text_search_current);
        for (int i = 0; i < this.mSearchMatches.size(); i++) {
            final int start = this.mSearchMatches.get(i);
            final int end = start + this.mSearchQuery.length();
            final int color = i == this.mCurrentMatchIndex ? currentColor : highlightColor;
            spannable.setSpan(new BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        this.mContentView.setText(spannable, TextView.BufferType.SPANNABLE);
    }

    private void clearSearchHighlight() {
        this.mSearchMatches.clear();
        this.mCurrentMatchIndex = -1;
        this.mContentView.setText(this.mContentView.getText().toString(), TextView.BufferType.NORMAL);
    }

    private void resetSearch() {
        this.mSearchQuery = "";
        clearSearchHighlight();
        updateFindToolbarActions();
    }

    private void scrollToMatch(final int matchStart) {
        this.mContentView.post(() -> {
            final Layout layout = this.mContentView.getLayout();
            if (layout == null) {
                this.mContentView.post(() -> scrollToMatch(matchStart));
                return;
            }
            final int line = layout.getLineForOffset(matchStart);
            final Rect lineBounds = new Rect();
            layout.getLineBounds(line, lineBounds);
            final int targetY = getContentVerticalOffset() + lineBounds.top
                    - this.mVerticalScroll.getHeight() / 4;
            this.mVerticalScroll.smoothScrollTo(0, Math.max(0, targetY));
            if (!this.mWordWrapEnabled) {
                final int targetX = (int) layout.getPrimaryHorizontal(matchStart)
                        - this.mHorizontalScroll.getWidth() / 4;
                this.mHorizontalScroll.smoothScrollTo(Math.max(0, targetX), 0);
            }
        });
    }

    private int getContentVerticalOffset() {
        int offset = 0;
        View view = this.mContentView;
        final View scrollContent = this.mVerticalScroll.getChildAt(0);
        while (view != null && view != scrollContent) {
            offset += view.getTop();
            final View parent = (View) view.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            view = parent;
        }
        return offset;
    }
}
