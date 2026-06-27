package com.p2p.android.ui;
import com.p2p.android.R;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.p2p.android.AppState;
import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager;

import java.io.File;
import java.util.List;

public class SearchFragment extends Fragment {

    private LinearLayout resultsContainer;
    private ProgressBar searchProgress;
    private TextView statusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        EditText searchField = view.findViewById(R.id.search_field);
        Button searchBtn = view.findViewById(R.id.search_button);
        Button browseBtn = view.findViewById(R.id.browse_button);
        resultsContainer = view.findViewById(R.id.results_container);
        searchProgress = view.findViewById(R.id.search_progress);
        statusText = view.findViewById(R.id.status_text);

        searchBtn.setOnClickListener(v -> {
            String query = searchField.getText().toString().trim();
            if (!query.isEmpty()) performSearch(query);
        });

        browseBtn.setOnClickListener(v -> performBrowse());

        searchField.setOnEditorActionListener((v, actionId, event) -> {
            String query = searchField.getText().toString().trim();
            if (!query.isEmpty()) performSearch(query);
            return true;
        });
    }

    private void performSearch(String query) {
        resultsContainer.removeAllViews();
        searchProgress.setVisibility(View.VISIBLE);
        statusText.setText("Searching for \"" + query + "\"...");

        AppState state = AppState.get(requireContext());
        if (!state.isConnected) {
            searchProgress.setVisibility(View.GONE);
            statusText.setText("⚠ Not connected. Go to Settings to configure the tracker.");
            return;
        }

        new Thread(() -> {
            List<PeerInfo> peers = state.trackerClient.query(query);
            requireActivity().runOnUiThread(() -> {
                searchProgress.setVisibility(View.GONE);
                if (peers.isEmpty()) {
                    statusText.setText("No one on the network is sharing \"" + query + "\" right now.");
                    return;
                }
                statusText.setText("Found on " + peers.size() + " peer(s):");
                for (PeerInfo peer : peers) {
                    peer.files.stream()
                            .filter(f -> f.name.equalsIgnoreCase(query))
                            .findFirst()
                            .ifPresent(fi -> resultsContainer.addView(buildResultCard(fi, peers)));
                }
            });
        }).start();
    }

    private void performBrowse() {
        resultsContainer.removeAllViews();
        searchProgress.setVisibility(View.VISIBLE);
        statusText.setText("Loading all files on the network...");

        AppState state = AppState.get(requireContext());
        if (!state.isConnected) {
            searchProgress.setVisibility(View.GONE);
            statusText.setText("⚠ Not connected. Go to Settings to configure the tracker.");
            return;
        }

        new Thread(() -> {
            List<PeerInfo> peers = state.trackerClient.browseAll();
            // Aggregate unique files (by name) -> list of peers that have them
            java.util.Map<String, FileInfo> filesByName = new java.util.LinkedHashMap<>();
            java.util.Map<String, List<PeerInfo>> peersByFile = new java.util.LinkedHashMap<>();
            for (PeerInfo peer : peers) {
                if (peer.files == null) continue;
                for (FileInfo f : peer.files) {
                    filesByName.putIfAbsent(f.name, f);
                    peersByFile.computeIfAbsent(f.name, k -> new java.util.ArrayList<>()).add(peer);
                }
            }
            requireActivity().runOnUiThread(() -> {
                searchProgress.setVisibility(View.GONE);
                if (filesByName.isEmpty()) {
                    statusText.setText("No files are being shared on the network right now.");
                    return;
                }
                statusText.setText(filesByName.size() + " file(s) available on the network:");
                for (java.util.Map.Entry<String, FileInfo> entry : filesByName.entrySet()) {
                    resultsContainer.addView(
                            buildResultCard(entry.getValue(), peersByFile.get(entry.getKey())));
                }
            });
        }).start();
    }

    private View buildResultCard(FileInfo fi, List<PeerInfo> peers) {
        // Inflated programmatically for simplicity
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(32, 28, 32, 28);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);
        card.setBackgroundResource(R.drawable.card_background);

        TextView name = new TextView(requireContext());
        name.setText(fi.name);
        name.setTextSize(16);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView details = new TextView(requireContext());
        details.setText(formatSize(fi.size) + "  ·  " + peers.size() + " source(s)");
        details.setTextSize(13);
        details.setTextColor(0xFF93A1AE);
        details.setPadding(0, 6, 0, 16);

        Button downloadBtn = new Button(requireContext());
        downloadBtn.setText("⬇  Download");
        downloadBtn.setTextColor(0xFFFFFFFF);
        downloadBtn.setBackgroundResource(R.drawable.button_primary);

        downloadBtn.setOnClickListener(v -> startDownload(fi, peers, downloadBtn));

        card.addView(name);
        card.addView(details);
        card.addView(downloadBtn);
        return card;
    }

    private void startDownload(FileInfo fi, List<PeerInfo> peers, Button btn) {
        AppState state = AppState.get(requireContext());
        File outFile = new File(state.getSharedFolder(), fi.name);
        DownloadManager.DownloadTask task = new DownloadManager.DownloadTask(
                fi.name, fi.size, outFile, peers);

        task.onProgressUpdate = t -> requireActivity().runOnUiThread(() ->
                btn.setText(t.getProgressText()));
        task.onComplete = t -> requireActivity().runOnUiThread(() -> {
            btn.setText("✓ Done!");
            btn.setEnabled(false);
            state.refreshSharedFiles();
        });
        task.onError = t -> requireActivity().runOnUiThread(() ->
                btn.setText("✗ Failed — tap to retry"));

        state.downloads.add(task);
        state.downloadManager.download(task);
        btn.setText("Starting...");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
