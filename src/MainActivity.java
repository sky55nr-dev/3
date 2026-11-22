package app.linklauncher.phone;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.InputFilter;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.Log;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String ADDRESS = "address";
    private static final int INSTALL_PERMISSION = 1;
    private static final int PICK_ICON = 2;
    private static final String ICON_STATE = "selectedIcon";
    private static final String OPEN_SHORTCUT = "app.linklauncher.phone.OPEN_SHORTCUT";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION";
    private static final Pattern EXPLICIT_SCHEME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private EditText linkInput;
    private EditText appName;
    private Button install;
    private Button chooseIcon;
    private Button resetIcon;
    private ImageView iconPreview;
    private TextView status;
    private byte[] selectedIcon;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String savedAddress = getPreferences(MODE_PRIVATE).getString(ADDRESS, "");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(view, insets);
            return insets;
        });

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(36), dp(24), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        root.addView(scroll);

        TextView heading = new TextView(this);
        heading.setText("링크 열기");
        heading.setTextSize(26);
        form.addView(heading);

        EditText input = new EditText(this);
        linkInput = input;
        input.setId(android.R.id.edit);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com");
        input.setContentDescription("웹사이트 주소");
        input.setText(savedAddress);
        form.addView(input);

        Button open = new Button(this);
        open.setText("저장하고 Chrome에서 열기");
        form.addView(open);

        appName = new EditText(this);
        appName.setId(android.R.id.text1);
        appName.setSingleLine(true);
        appName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        appName.setHint("설치할 앱 이름 (선택)");
        form.addView(appName);

        iconPreview = new ImageView(this);
        iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconPreview.setContentDescription("설치할 앱 아이콘 미리보기");
        form.addView(iconPreview, new LinearLayout.LayoutParams(dp(64), dp(64)));

        chooseIcon = new Button(this);
        chooseIcon.setText("앱 아이콘 선택");
        chooseIcon.setOnClickListener(view -> pickIcon());
        form.addView(chooseIcon);

        resetIcon = new Button(this);
        resetIcon.setText("기본 아이콘으로 바꾸기");
        resetIcon.setOnClickListener(view -> {
            showIcon(null);
            status.setText("");
        });
        form.addView(resetIcon);
        showIcon(state == null ? null : state.getByteArray(ICON_STATE));

        install = new Button(this);
        install.setText("새 앱 만들고 설치");
        form.addView(install);

        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(status);
        install.setOnClickListener(view -> createApp());
        open.setOnClickListener(view -> {
            String address = readAddress(input);
            if (address != null) {
                input.setText(address);
                getPreferences(MODE_PRIVATE).edit().putString(ADDRESS, address).apply();
                openInChrome(address, status);
            }
        });

        setContentView(root);
        open.requestFocus();
        if (OPEN_SHORTCUT.equals(getIntent().getAction())) {
            String address = normalizeAddress(getIntent().getDataString());
            if (address != null) {
                input.setText(address);
                if (state == null && openInChrome(address, status)) {
                    finish();
                }
            } else {
                status.setText("이 바로가기의 웹사이트 주소를 열 수 없습니다.");
            }
        } else if (state == null && !savedAddress.isEmpty()) {
            openInChrome(savedAddress, status);
        }
    }

    private void createApp() {
        if (Build.VERSION.SDK_INT < 26) {
            status.setText("새 앱을 만들려면 Android 8.0 이상이 필요합니다.");
            return;
        }
        String address = readAddress(linkInput);
        if (address == null) {
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("설치하려면 설정에서 이 출처를 허용하세요.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", getPackageName(), null));
            try {
                startActivityForResult(settings, INSTALL_PERMISSION);
            } catch (ActivityNotFoundException | SecurityException exception) {
                status.setText("기기 설정에서 링크 열기의 앱 설치를 허용해 주세요.");
            }
            return;
        }
        String name = appName.getText().toString().trim();
        Uri url = Uri.parse(address).normalizeScheme();
        String label = name.isEmpty() ? url.getHost() : name;
        if (label.length() > 40) {
            label = label.substring(0, 40);
        }
        String appLabel = label;
        byte[] icon = selectedIcon;
        setFormBusy(true);
        status.setText("설치 파일을 만드는 중입니다.");
        new Thread(() -> {
            try {
                File apk = ApkFactory.create(getApplicationContext(), appLabel, url.toString(), icon);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        openInstaller(apk);
                    }
                });
            } catch (Exception | LinkageError exception) {
                Log.e("LinkLauncher", "Could not create APK", exception);
                runOnUiThread(() -> {
                    setFormBusy(false);
                    status.setText("설치 파일을 만들 수 없습니다. 다시 시도해 주세요.");
                });
            }
        }, "apk-generator").start();
    }

    private void openInstaller(File apk) {
        Uri uri = new Uri.Builder().scheme("content").authority(GeneratedApkProvider.AUTHORITY)
                .appendPath(apk.getName()).build();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, GeneratedApkProvider.MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("APK", uri));
        try {
            startActivity(intent);
            status.setText("안드로이드 설치 화면에서 설치를 확인하세요.");
        } catch (ActivityNotFoundException | SecurityException exception) {
            status.setText("안드로이드 설치 화면을 열 수 없습니다.");
        } finally {
            setFormBusy(false);
        }
    }

    private void pickIcon() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(picker, PICK_ICON);
        } catch (ActivityNotFoundException exception) {
            status.setText("이미지 선택기를 열 수 없습니다.");
        }
    }

    private void loadIcon(Uri uri) {
        setFormBusy(true);
        status.setText("아이콘 이미지를 불러오는 중입니다.");
        new Thread(() -> {
            try {
                byte[] icon = IconImage.load(getContentResolver(), uri);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        showIcon(icon);
                        setFormBusy(false);
                        status.setText("");
                    }
                });
            } catch (java.io.IOException | RuntimeException exception) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        setFormBusy(false);
                        status.setText("이미지를 읽을 수 없습니다. 다른 사진이나 파일을 선택하세요.");
                    }
                });
            }
        }, "icon-loader").start();
    }

    private void showIcon(byte[] icon) {
        selectedIcon = icon;
        if (icon == null) {
            iconPreview.setImageResource(getApplicationInfo().icon);
        } else {
            iconPreview.setImageBitmap(BitmapFactory.decodeByteArray(icon, 0, icon.length));
        }
        resetIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
    }

    private void setFormBusy(boolean busy) {
        install.setEnabled(!busy);
        chooseIcon.setEnabled(!busy);
        resetIcon.setEnabled(!busy);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putByteArray(ICON_STATE, selectedIcon);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INSTALL_PERMISSION && Build.VERSION.SDK_INT >= 26
                && getPackageManager().canRequestPackageInstalls()) {
            createApp();
        }
        if (requestCode == PICK_ICON && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            loadIcon(data.getData());
        }
    }

    private boolean openInChrome(String address, TextView status) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address).normalizeScheme());
        intent.setPackage(CHROME_PACKAGE);
        Bundle extras = new Bundle();
        extras.putBinder(CUSTOM_TAB_SESSION, null);
        intent.putExtras(extras);
        try {
            startActivity(intent);
            status.setText("");
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            status.setText("Chrome을 설치하거나 활성화한 뒤 다시 열어 주세요.");
            return false;
        }
    }

    private String readAddress(EditText input) {
        String address = normalizeAddress(input.getText().toString());
        if (address == null) {
            input.setError("올바른 웹사이트 주소를 입력하세요.");
        }
        return address;
    }

    private static String normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        String address = value.trim();
        if (address.startsWith("//")) {
            address = "https:" + address;
        } else if (!EXPLICIT_SCHEME.matcher(address).matches()) {
            address = "https://" + address;
        }
        try {
            URI url = new URI(address);
            String scheme = url.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && url.getHost() != null && url.getRawUserInfo() == null && url.getPort() <= 65535) {
                return url.toASCIIString();
            }
        } catch (URISyntaxException ignored) {
        }
        return null;
    }

    private void applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets edges = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
