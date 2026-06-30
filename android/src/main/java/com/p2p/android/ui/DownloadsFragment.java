package com.p2p.android.ui;
import com.p2p.android.R;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import androidx.annotation.*;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import com.p2p.android.AppState;
import com.p2p.core.transfer.DownloadManager;

import java.io.File;

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
            status.setText("Complete");
            status.setTextColor(0xFF46C46A);
        });
        task.onError = t -> requireActivity().runOnUiThread(() -> {
            status.setText("Failed: " + t.errorMessage);
            status.setTextColor(0xFFE5564E);
        });

        card.setOnClickListener(v -> openDownload(task));

        card.addView(name);
        card.addView(bar);
        card.addView(status);
        return card;
    }

    /** Open a completed download with a suitable app; fall back to the shared folder. */
    private void openDownload(DownloadManager.DownloadTask task) {
        if (task.state != DownloadManager.DownloadState.COMPLETE) {
            Toast.makeText(requireContext(), "Download not finished yet", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(AppState.get(requireContext()).getSharedFolder(), task.filename);
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File not found in shared folder", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    ext == null ? "" : ext.toLowerCase());
            if (mime == null) mime = "*/*";

            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, mime);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(open, "Open with"));
        } catch (ActivityNotFoundException e) {
            openSharedFolder();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not open file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Fallback: open a file manager at the Downloads location. */
    private void openSharedFolder() {
        try {
            Intent intent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Saved in: " + AppState.get(requireContext()).sharedFolderPath, Toast.LENGTH_LONG).show();
        }
    }

    private String stateLabel(DownloadManager.DownloadState s) {
        if (s == DownloadManager.DownloadState.QUEUED)      return "Queued";
        if (s == DownloadManager.DownloadState.CONNECTING)  return "Connecting...";
        if (s == DownloadManager.DownloadState.DOWNLOADING) return "Downloading";
        if (s == DownloadManager.DownloadState.VERIFYING)   return "Verifying...";
        if (s == DownloadManager.DownloadState.COMPLETE)    return "Complete";
        if (s == DownloadManager.DownloadState.FAILED)      return "Failed";
        return "Paused";
    }

    private int stateColor(DownloadManager.DownloadState s) {
        if (s == DownloadManager.DownloadState.COMPLETE)    return 0xFF46C46A;
        if (s == DownloadManager.DownloadState.FAILED)      return 0xFFE5564E;
        if (s == DownloadManager.DownloadState.DOWNLOADING ||
            s == DownloadManager.DownloadState.CONNECTING)  return 0xFF0E8C77;
        if (s == DownloadManager.DownloadState.VERIFYING ||
            s == DownloadManager.DownloadState.PAUSED)      return 0xFFE0A33A;
        return 0xFF888888;
    }
}
