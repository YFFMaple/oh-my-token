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
 * 选定某一天查看统计。
 */
final class DateDialog extends DialogWrapper {

    private final JBTextField field = new JBTextField(LocalDate.now().toString(), 10);

    DateDialog() {
        super((com.intellij.openapi.project.Project) null);
        setTitle(OhMyTokenBundle.message("date.dialog.title"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(OhMyTokenBundle.message("date.dialog.label"), field)
                .getPanel();
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            LocalDate.parse(field.getText().trim());
            return null;
        } catch (DateTimeParseException e) {
            return new ValidationInfo(
                    OhMyTokenBundle.message("date.dialog.invalid"), field);
        }
    }

    /** 校验通过后调用方读取 */
    LocalDate date() {
        return LocalDate.parse(field.getText().trim());
    }
}
