package de.danoeh.antennapod.automotive;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.automotive.databinding.PodcastSearchFragmentBinding;
import de.danoeh.antennapod.net.discovery.CombinedSearcher;
import de.danoeh.antennapod.net.discovery.PodcastSearchResult;
import de.danoeh.antennapod.ui.common.Keyboard;
import de.danoeh.antennapod.ui.discovery.OnlineSearchAdapter;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.List;

public class PodcastSearchFragment extends Fragment {
    private static final String TAG = "PodcastSearchFragment";

    private final List<PodcastSearchResult> results = new ArrayList<>();
    private PodcastSearchFragmentBinding viewBinding;
    private OnlineSearchAdapter adapter;
    private Disposable disposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = PodcastSearchFragmentBinding.inflate(inflater, container, false);
        adapter = new OnlineSearchAdapter(requireContext(), results);
        viewBinding.resultList.setAdapter(adapter);
        viewBinding.resultList.setOnItemClickListener(
                (parent, view, position, id) -> confirmSubscribe(results.get(position)));
        viewBinding.searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                Keyboard.hide(requireActivity());
                search(view.getText().toString());
                return true;
            }
            return false;
        });
        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.search_online);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
        viewBinding = null;
    }

    private void search(String query) {
        if (query.trim().isEmpty()) {
            return;
        }
        if (disposable != null) {
            disposable.dispose();
        }
        results.clear();
        adapter.notifyDataSetChanged();
        viewBinding.emptyView.setVisibility(View.GONE);
        viewBinding.progressBar.setVisibility(View.VISIBLE);
        disposable = new CombinedSearcher().search(query)
                .subscribe(searchResults -> {
                    viewBinding.progressBar.setVisibility(View.GONE);
                    for (PodcastSearchResult result : searchResults) {
                        if (result.feedUrl != null) {
                            results.add(result);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    if (results.isEmpty()) {
                        viewBinding.emptyView.setText(getString(R.string.no_results_for_query, query));
                        viewBinding.emptyView.setVisibility(View.VISIBLE);
                    }
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    viewBinding.progressBar.setVisibility(View.GONE);
                    viewBinding.emptyView.setText(error.toString());
                    viewBinding.emptyView.setVisibility(View.VISIBLE);
                });
    }

    private void confirmSubscribe(PodcastSearchResult result) {
        Context context = requireContext();
        new MaterialAlertDialogBuilder(context)
                .setTitle(result.title)
                .setMessage(result.author)
                .setPositiveButton(R.string.subscribe_label,
                        (dialog, which) -> FeedSubscriber.subscribe(context, result.feedUrl))
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
