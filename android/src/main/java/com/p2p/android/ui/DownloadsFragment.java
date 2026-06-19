package com.p2p.android.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.p2p.android.AppState;
import com.p2p.core.transfer.DownloadManager;

public class DownloadsFragment extends Fragment {

    private LinearLayout container;
    private TextView emptyText;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        container = view.findViewById(R.id.downloads_container);
        emptyText  = view.findViewById(R.id.empty_text);
        renderDownloads();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderDownloads();
    }

    private void renderDownloads() {
        if (container == null) return;
        container.removeAllViews();
        AppState state = AppState.get(requireContext());

        if (state.downloads.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);
        for (DownloadManager.DownloadTask task : state.downloads) {
            container.addView(buildCard(task));
        }
    }

    private View buildCard(DownloadManager.DownloadTask task) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 28, 32, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 16);
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.card_background);

        TextView name = new TextView(requireContext());
        name.setText(task.filename);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        ProgressBar bar = new ProgressBar(requireContext(),
                null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int)(task.progress * 100));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16);
        barLp.setMargins(0, 12, 0, 8);
        bar.setLayoutParams(barLp);

        TextView status = new TextView(requireContext());
        status.setText(stateLabel(task.state));
        status.setTextColor(stateColor(task.state));
        status.setTextSize(12);

        task.onProgressUpdate = t -> requireActivity().runOnUiThread(() -> {
            bar.setProgress((int)(t.progress * 100));
            status.setText(stateLabel(t.state));
            status.setTextColor(stateColor(t.state));
        });
        task.onComplete = t -> requireActivity().runOnUiThread(() -> {
            bar.setProgress(100);
            status.setText("✓ Complete");
            status.setTextColor(0xFF4CAF50);
        });
        task.onError = t -> requireActivity().runOnUiThread(() -> {
            status.setText("✗ Failed: " + t.errorMessage);
            status.setTextColor(0xFFF44336);
        });

        card.addView(name);
        card.addView(bar);
        card.addView(status);
        return card;
    }

    private String stateLabel(DownloadManager.DownloadState s) {
        if (s == DownloadManager.DownloadState.QUEUED)      return "Queued";
        if (s == DownloadManager.DownloadState.CONNECTING)  return "Connecting...";
        if (s == DownloadManager.DownloadState.DOWNLOADING) return "Downloading";
        if (s == DownloadManager.DownloadState.VERIFYING)   return "Verifying...";
        if (s == DownloadManager.DownloadState.COMPLETE)    return "✓ Complete";
        if (s == DownloadManager.DownloadState.FAILED)      return "✗ Failed";
        return "Paused";
    }

    private int stateColor(DownloadManager.DownloadState s) {
        if (s == DownloadManager.DownloadState.COMPLETE)    return 0xFF4CAF50;
        if (s == DownloadManager.DownloadState.FAILED)      return 0xFFF44336;
        if (s == DownloadManager.DownloadState.DOWNLOADING ||
            s == DownloadManager.DownloadState.CONNECTING)  return 0xFF7C6EF7;
        if (s == DownloadManager.DownloadState.VERIFYING ||
            s == DownloadManager.DownloadState.PAUSED)      return 0xFFFFA726;
        return 0xFF888888;
    }
}
