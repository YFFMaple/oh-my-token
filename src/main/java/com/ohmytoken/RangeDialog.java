package com.ohmytoken;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 选定一个日期范围（起止日期，含两端）。
 */
final class RangeDialog extends DialogWrapper {

    /** 与 TokenStats.KEEP_DAYS 一致：只保留最近 31 天数据 */
    private static final int MAX_DAYS = 31;

    private final JBTextField fromField;
    private final JBTextField toField;

    RangeDialog(@Nullable LocalDate from, @Nullable LocalDate to) {
        super((com.intellij.openapi.project.Project) null);
        setTitle(OhMyTokenBundle.message("range.dialog.title"));
        LocalDate today = LocalDate.now();
        LocalDate defaultFrom = from != null ? from : today.minusDays(6);
        LocalDate defaultTo = to != null ? to : today;
        fromField = new JBTextField(defaultFrom.toString(), 10);
        toField = new JBTextField(defaultTo.toString(), 10);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(OhMyTokenBundle.message("range.dialog.from"), fromField)
                .addLabeledComponent(OhMyTokenBundle.message("range.dialog.to"), toField)
                .addComponent(new javax.swing.JLabel(
                        OhMyTokenBundle.message("range.dialog.hint", MAX_DAYS)))
                .getPanel();
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromField.getText().trim());
        } catch (DateTimeParseException e) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("range.dialog.invalid"), fromField);
        }
        try {
            to = LocalDate.parse(toField.getText().trim());
        } catch (DateTimeParseException e) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("range.dialog.invalid"), toField);
        }
        if (from.isAfter(to)) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("range.dialog.order"), fromField);
        }
        LocalDate today = LocalDate.now();
        if (to.isAfter(today)) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("range.dialog.future"), toField);
        }
        if (from.isBefore(today.minusDays(MAX_DAYS - 1))) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("range.dialog.retention", MAX_DAYS), fromField);
        }
        return null;
    }

    /** 校验通过后调用方读取 */
    LocalDate from() {
        return LocalDate.parse(fromField.getText().trim());
    }

    LocalDate to() {
        return LocalDate.parse(toField.getText().trim());
    }
}
