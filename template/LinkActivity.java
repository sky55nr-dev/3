package app.linklauncher.generated;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class LinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("address.txt"), StandardCharsets.UTF_8))) {
            Intent chrome = new Intent(Intent.ACTION_VIEW, Uri.parse(reader.readLine()));
            chrome.setPackage("com.android.chrome");
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null);
            chrome.putExtras(extras);
            startActivity(chrome);
            finish();
        } catch (ActivityNotFoundException | SecurityException exception) {
            showMessage("Chrome을 설치하거나 활성화한 뒤 다시 실행하세요.");
        } catch (IOException exception) {
            showMessage("저장된 링크를 읽을 수 없습니다.");
        }
    }

    private void showMessage(String message) {
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setText(message);
        setContentView(text);
    }
}
