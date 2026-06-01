package com.example.tinyiptv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<String> filePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    loadPlaylist(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Убираем заголовок окна
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        
        hideSystemUI();

        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // Слушатель ошибок
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String msg = "Ошибка: ";
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) msg += "Файл не найден";
                else if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) msg += "Нет сети";
                else msg += error.getLocalizedMessage();
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.btn_url).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.btn_file).setOnClickListener(v -> filePicker.launch("*/*"));

        initTouchLogic();

        String lastUri = prefs.getString("last_playlist", "");
        if (!lastUri.isEmpty()) {
            loadPlaylist(Uri.parse(lastUri));
        } else {
            drawer.openDrawer(GravityCompat.START);
        }
    }

    // Полный экран без полосок
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // Обработка физических кнопок (вперед/назад)
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT) {
                playChannel((currentIdx + 1) % channels.size());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                playChannel((currentIdx - 1 + channels.size()) % channels.size());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void loadPlaylist(Uri uri) {
        prefs.edit().putString("last_playlist", uri.toString()).apply();
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line, name = "Unknown";
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.lastIndexOf(",");
                        if (comma != -1) name = line.substring(comma + 1);
                    } else if (!line.isEmpty() && !line.startsWith("#")) {
                        tmp.add(new String[]{name, line});
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка чтения M3U", Toast.LENGTH_SHORT).show());
            }
            
            runOnUiThread(() -> {
                channels = tmp;
                updateList();
                if (!channels.isEmpty()) {
                    int lastIdx = prefs.getInt("last_idx", 0);
                    playChannel(lastIdx < channels.size() ? lastIdx : 0);
                }
            });
        }).start();
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = idx;
        MediaItem mediaItem = MediaItem.fromUri(channels.get(currentIdx)[1]);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
        prefs.edit().putInt("last_idx", currentIdx).apply();
    }

    private void updateList() {
        ListView lv = findViewById(R.id.channel_list);
        List<String> names = new ArrayList<>();
        for (String[] c : channels) names.add(c[0]);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            playChannel(pos);
            drawer.closeDrawer(GravityCompat.START);
        });
    }

    private void initTouchLogic() {
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (Math.abs(vY) > Math.abs(vX)) {
                    if (vY > 0) playChannel((currentIdx - 1 + channels.size()) % channels.size());
                    else playChannel((currentIdx + 1) % channels.size());
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e.getX() < (float) playerView.getWidth() / 3) {
                    drawer.openDrawer(GravityCompat.START);
                    return true;
                }
                return false;
            }
        });
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true; 
        });
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this).setTitle("Вставь URL").setView(input)
            .setPositiveButton("OK", (d, w) -> loadPlaylist(Uri.parse(input.getText().toString().trim()))).show();
    }

    @Override protected void onResume() { super.onResume(); hideSystemUI(); player.play(); }
    @Override protected void onStop() { super.onStop(); player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
