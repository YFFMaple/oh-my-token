package com.ohmytoken;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings → Tools → Oh My Token
 */
public final class OhMyTokenConfigurable implements Configurable {

    private JComboBox<DisplayRange> rangeCombo;
    private JBCheckBox showRate;
    private JBCheckBox showTotal;
    private JBCheckBox notifyAlerts;
    private JBCheckBox autoCtx;
    private JBTextField limitField;
    private JBTextField weeklyLimitField;
    private JBTextField dirField;
    private JBTextField ctxWindowField;
    private JBTextField ctxWarnField;
    private JPanel panel;

    @Override
    public String getDisplayName() {
        return "Oh My Token";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rangeCombo = new JComboBox<>(DisplayRange.values());
        showRate = new JBCheckBox(OhMyTokenBundle.message("settings.show.rate"));
        showTotal = new JBCheckBox(OhMyTokenBundle.message("settings.show.total"));
        notifyAlerts = new JBCheckBox(OhMyTokenBundle.message("settings.notify"));
        autoCtx = new JBCheckBox(OhMyTokenBundle.message("settings.auto.context"));
        limitField = new JBTextField(12);
        weeklyLimitField = new JBTextField(12);
        dirField = new JBTextField(30);
        ctxWindowField = new JBTextField(12);
        ctxWarnField = new JBTextField(4);

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(OhMyTokenBundle.message("settings.default.range"), rangeCombo)
                .addComponent(showRate)
                .addComponent(showTotal)
                .addComponent(notifyAlerts)
                .addLabeledComponent(OhMyTokenBundle.message("settings.5h.limit"), limitField)
                .addLabeledComponent(OhMyTokenBundle.message("settings.weekly.limit"), weeklyLimitField)
                .addComponent(autoCtx)
                .addLabeledComponent(OhMyTokenBundle.message("settings.context.window"), ctxWindowField)
                .addLabeledComponent(OhMyTokenBundle.message("settings.ctx.warn"), ctxWarnField)
                .addLabeledComponent(OhMyTokenBundle.message("settings.projects.dir"), dirField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        OhMyTokenSettings.State s = OhMyTokenSettings.getInstance().getState();
        return DisplayRange.byName(s.displayRange) != rangeCombo.getSelectedItem()
                || s.showRate != showRate.isSelected()
                || s.showTotal != showTotal.isSelected()
                || s.notifyAlerts != notifyAlerts.isSelected()
                || s.fiveHourLimit != parseLong(limitField, 0)
                || s.weeklyLimit != parseLong(weeklyLimitField, 0)
                || s.autoContextWindow != autoCtx.isSelected()
                || s.contextWindow != parseLong(ctxWindowField, 200_000)
                || s.ctxWarnPercent != (int) parseLong(ctxWarnField, 80)
                || !s.projectsDir.equals(dirField.getText().trim());
    }

    @Override
    public void apply() {
        OhMyTokenSettings.State s = OhMyTokenSettings.getInstance().getState();
        Object selected = rangeCombo.getSelectedItem();
        String newRange = (selected instanceof DisplayRange r ? r : DisplayRange.TODAY).name();
        if (!s.displayRange.equals(newRange)) {
            // 改了默认范围就退出"选定日期"模式
            s.customDate = "";
        }
        s.displayRange = newRange;
        s.showRate = showRate.isSelected();
        s.showTotal = showTotal.isSelected();
        s.notifyAlerts = notifyAlerts.isSelected();
        s.fiveHourLimit = parseLong(limitField, 0);
        s.weeklyLimit = parseLong(weeklyLimitField, 0);
        s.autoContextWindow = autoCtx.isSelected();
        s.contextWindow = parseLong(ctxWindowField, 200_000);
        int warn = (int) parseLong(ctxWarnField, 80);
        s.ctxWarnPercent = Math.max(1, Math.min(100, warn));
        s.projectsDir = dirField.getText().trim();
    }

    @Override
    public void reset() {
        OhMyTokenSettings.State s = OhMyTokenSettings.getInstance().getState();
        rangeCombo.setSelectedItem(DisplayRange.byName(s.displayRange));
        showRate.setSelected(s.showRate);
        showTotal.setSelected(s.showTotal);
        notifyAlerts.setSelected(s.notifyAlerts);
        limitField.setText(s.fiveHourLimit > 0 ? Long.toString(s.fiveHourLimit) : "");
        weeklyLimitField.setText(s.weeklyLimit > 0 ? Long.toString(s.weeklyLimit) : "");
        autoCtx.setSelected(s.autoContextWindow);
        ctxWindowField.setText(Long.toString(s.contextWindow));
        ctxWarnField.setText(Integer.toString(s.ctxWarnPercent));
        dirField.setText(s.projectsDir);
    }

    /** 非法输入返回 fallback */
    private static long parseLong(JBTextField field, long fallback) {
        try {
            return Math.max(0, Long.parseLong(field.getText().trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
