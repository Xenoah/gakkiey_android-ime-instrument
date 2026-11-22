package com.xenoah.gakkikeys;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/** The input method: the keyboard is a playable instrument. */
public final class GakkiIME extends InputMethodService implements InstrumentView.Host {
    private SoundEngine engine;
    private InstrumentView view;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new SoundEngine();
    }

    @Override
    public View onCreateInputView() {
        if (view != null) view.releaseAll();
        view = new InstrumentView(this, engine.core(this), this);
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view != null) view.onStartInput(info.inputType);
        engine.start(this);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (view != null) view.releaseAll();
        engine.stop();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        if (view != null) view.releaseAll();
        engine.stop();
        super.onWindowHidden();
    }

    @Override
    public void onDestroy() {
        engine.stop();
        super.onDestroy();
    }

    // ---- typing
    private void enter(InputConnection ic) {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei != null) {
            int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
            boolean noAction = (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            boolean multi = (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            if (!noAction && !multi && action != EditorInfo.IME_ACTION_NONE
                    && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action);
                return;
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }

    @Override
    public void onTypeKey(String code) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (code) {
            case "DEL": {
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) ic.commitText("", 1);
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                return;
            }
            case "SPACE":
                ic.commitText(" ", 1);
                return;
            case "ENTER":
                enter(ic);
                return;
        }
        ic.commitText(code, 1);
    }

    // ---- Host
    @Override
    public void onNextIme() {
        if (!switchToNextInputMethod(false)) onPickIme();
    }

    @Override
    public void onPickIme() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override
    public void onHide() {
        requestHideSelf(0);
    }

    @Override
    public void onCommit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }
}
