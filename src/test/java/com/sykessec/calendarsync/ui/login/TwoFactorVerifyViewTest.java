package com.sykessec.calendarsync.ui.login;

import com.sykessec.calendarsync.security.PendingSecondFactor;
import com.sykessec.calendarsync.security.SecondFactorAuthenticationFilter;
import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks the verify page's component tree directly, in the style of
 * ExportSettingsFormTest: no Spring context, no UI, no vaadin-dev-server.
 *
 * This is the only kind of test that can see the defect these assertions
 * pin down. TwoFactorLoginTest drives the same feature over real HTTP and
 * passes whether or not the page is usable, because a Vaadin view is rendered
 * client-side - the bootstrap response an HttpClient receives contains none of
 * the markup below. The page shipped with a vaadin-button carrying
 * type="submit", which a browser will not treat as a submit control because a
 * custom element is not form-associated, so the form could never be posted and
 * the second factor could never be completed. Every server-side test still
 * passed.
 */
class TwoFactorVerifyViewTest {

    private static final PendingSecondFactor PENDING =
            PendingSecondFactor.forUser(1L, "admin", null);

    @Test
    void postsToTheUrlTheSecondFactorFilterListensOn() {
        Element form = buildForm();

        assertThat(form.getTag()).isEqualTo("form");
        assertThat(form.getAttribute("method")).isEqualTo("post");
        assertThat(form.getAttribute("action"))
                .isEqualTo(SecondFactorAuthenticationFilter.PROCESSING_URL);
    }

    /**
     * The regression this class exists for. A native button is the only thing
     * in the form that can submit it; asserting on the tag rather than on the
     * component type is deliberate, since the tag is what the browser acts on.
     */
    @Test
    void theSubmitControlIsANativeButton() {
        List<Element> buttons = descendants(buildForm()).stream()
                .filter(element -> "button".equals(element.getTag()))
                .toList();

        assertThat(buttons).hasSize(1);
        assertThat(buttons.getFirst().getAttribute("type")).isEqualTo("submit");
    }

    /**
     * States the rule rather than the symptom: a vaadin-button inside this form
     * is always wrong, wherever in the form it appears and whatever attributes
     * it carries.
     */
    @Test
    void noVaadinButtonAppearsInTheForm() {
        assertThat(descendants(buildForm()))
                .extracting(Element::getTag)
                .doesNotContain("vaadin-button");
    }

    @Test
    void carriesTheFieldNamesTheFilterReadsOffTheRequest() {
        List<Element> inputs = descendants(buildForm()).stream()
                .filter(element -> "input".equals(element.getTag()))
                .toList();

        assertThat(inputs).extracting(element -> element.getAttribute("name"))
                .contains("code", "recoveryCode");
    }

    private static Element buildForm() {
        return new TwoFactorVerifyView().buildForm(PENDING, false).getElement();
    }

    private static List<Element> descendants(Element root) {
        List<Element> found = new ArrayList<>();
        root.getChildren().filter(child -> !child.isTextNode()).forEach(child -> {
            found.add(child);
            found.addAll(descendants(child));
        });
        return found;
    }
}
