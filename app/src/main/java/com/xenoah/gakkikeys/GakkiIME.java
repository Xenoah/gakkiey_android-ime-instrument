package com.xenoah.gakkikeys;

import android.content.ComponentName;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

/**
 * The input method. Each instrument (and the English sound keyboard) is registered as an
 * input-method subtype ("language"), so it can be switched like any other keyboard language.
 */
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
        applySubtype(currentSubtype());
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        applySubtype(currentSubtype());
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

    // ---- subtypes ("languages")
    private InputMethodManager imm() {
        return (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    }

    private InputMethodSubtype currentSubtype() {
        InputMethodManager imm = imm();
        return imm == null ? null : imm.getCurrentInputMethodSubtype();
    }

    @Override
    protected void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype);
        applySubtype(subtype);
    }

    private void applySubtype(InputMethodSubtype st) {
        if (view == null || st == null) return;
        String m = st.containsExtraValueKey("mode") ? st.getExtraValueOf("mode") : null;
        if (m != null) view.applyMode(m);
    }

    /** A tab was tapped: move the system's current subtype along so the language indicator matches. */
    @Override
    public void onModeSelected(String mode) {
        InputMethodManager imm = imm();
        if (imm == null) return;
        String id = new ComponentName(this, GakkiIME.class).flattenToShortString();
        for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (!imi.getPackageName().equals(getPackageName())) continue;
            for (InputMethodSubtype st : imm.getEnabledInputMethodSubtypeList(imi, true)) {
                if (st.containsExtraValueKey("mode") && mode.equals(st.getExtraValueOf("mode"))) {
                    InputMethodSubtype cur = imm.getCurrentInputMethodSubtype();
                    if (cur == null || !cur.equals(st)) switchInputMethod(imi.getId(), st);
                    return;
                }
            }
            return;
        }
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
        InputMethodManager imm = imm();
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
