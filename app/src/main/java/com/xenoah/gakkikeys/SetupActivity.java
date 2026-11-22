package com.xenoah.gakkikeys;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher screen: how to enable the keyboard + a box to try it in. */
public final class SetupActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setBackgroundColor(0xFF0D0E14);

        TextView title = new TextView(this);
        title.setText("楽器キー");
        title.setTextSize(30);
        title.setTextColor(0xFFFF4FA3);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("キーボードがドラム・ピアノ・シンセになるIME");
        sub.setTextColor(0xFF9AA0B8);
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, pad);
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, pad / 2);
        root.addView(status);

        root.addView(button("① キーボード設定で「楽器キー」をON", () ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("② 使う楽器（言語）を選ぶ（全部ONが標準）", () -> {
            Intent i = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
            i.putExtra(Settings.EXTRA_INPUT_METHOD_ID,
                    new android.content.ComponentName(this, GakkiIME.class).flattenToShortString());
            i.putExtra(Intent.EXTRA_TITLE, "楽器キー");
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        }));
        root.addView(button("③ 入力方法を「楽器キー」に切り替え", () -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }));

        TextView how = new TextView(this);
        how.setTextColor(0xFFDDE0EA);
        how.setTextSize(14);
        how.setLineSpacing(0, 1.3f);
        how.setPadding(0, pad, 0, pad / 2);
        how.setText("④ 下の欄をタップして演奏\n\n"
                + "楽器はそれぞれキーボードの「言語」として登録されています。🌐（またはシステムのキーボード切替）で、"
                + "サウンドキーボード（英語）→ ドラム → 鍵盤 → 電話機 → 声 と切り替わります。上のタブでも切替できます。\n\n"
                + "・⌨ サウンドキーボード（英語）：キーごとにランダムな効果音（⚙「タイプ音」）。123は電話型テンキーでプッシュ音\n"
                + "・🥁 ドラム：TR-808風。実際のドラムセット配置（⚙でパッド配置にも）\n"
                + "・🎹 鍵盤：⚙で音色（ピアノ / LEAD / SQUARE / PAD / BASS）と幅（1〜3オクターブ）\n"
                + "・☎ 電話機：公衆電話風テンキーでDTMF（プッシュ音）\n"
                + "・🗣 声：英語の発音記号キー。押すと発音、▶で単語として喋る（⚙で男性 / 女性）\n"
                + "・✎：ONにすると、鳴らした音（ドレミ・数字・発音記号など）を入力欄にも入力\n"
                + "・🌐：次のキーボード / 言語（長押しで一覧）\n\n"
                + "日本語入力はありません。日本語は🌐でGboardなどに切り替えてください。音量はメディア音量に連動します。");
        root.addView(how);

        EditText test = new EditText(this);
        test.setHint("ここをタップして試す");
        test.setHintTextColor(0xFF5A5D6E);
        test.setTextColor(Color.WHITE);
        test.setMinLines(2);
        test.setGravity(Gravity.TOP);
        root.addView(test, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0D0E14);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateStatus();
    }

    private void updateStatus() {
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo i : imm.getEnabledInputMethodList())
                if (i.getPackageName().equals(getPackageName())) enabled = true;
        }
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = cur != null && cur.startsWith(getPackageName() + "/");
        if (selected) {
            status.setText("✔ 楽器キーを使用中");
            status.setTextColor(0xFF38E1FF);
        } else if (enabled) {
            status.setText("✔ 有効化済み → ②で切り替えてください");
            status.setTextColor(0xFFFFD166);
        } else {
            status.setText("まだ有効になっていません → ①から");
            status.setTextColor(0xFFFF4FA3);
        }
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }
}
