import * as licon from 'common/licon';
import * as xhr from 'common/xhr';
import { emojiPicker } from './emojiPicker';

lichess.load.then(() => {
  $('.emoji-details').on('toggle', function (this: HTMLDetailsElement) {
    const details = this;
    const close = () => details.removeAttribute('open');
    emojiPicker(details.querySelector('.emoji-picker')!, close);
  });

  const localPrefs: [string, string, string, boolean][] = [
    ['behavior', 'arrowSnap', 'arrow.snap', true],
    ['behavior', 'courtesy', 'courtesy', false],
    ['behavior', 'scrollMoves', 'scrollMoves', true],
    ['notification', 'playBellSound', 'playBellSound', true],
  ];

  $('.security table form').on('submit', function (this: HTMLFormElement) {
    xhr.text(this.action, { method: 'post', body: new URLSearchParams(new FormData(this) as any) });
    $(this).parent().parent().remove();
    return false;
  });

  $('form.autosubmit').each(function (this: HTMLFormElement) {
    const form = this,
      $form = $(form),
      showSaved = () => $form.find('.saved').removeClass('none');
    computeBitChoices($form, 'behavior.submitMove');
    $form.find('input').on('change', function (this: HTMLInputElement) {
      computeBitChoices($form, 'behavior.submitMove');
      localPrefs.forEach(([categ, name, storeKey]) => {
        if (this.name == `${categ}.${name}`) {
          lichess.storage.boolean(storeKey).set(this.value == '1');
          showSaved();
        }
      });
      xhr.formToXhr(form).then(() => {
        showSaved();
        lichess.storage.fire('reload-round-tabs');
      });
    });
  });

  localPrefs.forEach(([categ, name, storeKey, def]) =>
    $(`#ir${categ}_${name}_${lichess.storage.boolean(storeKey).getOrDefault(def) ? 1 : 0}`).prop(
      'checked',
      true,
    ),
  );

  $('form[action="/account/oauth/token/create"]').each(function (this: HTMLFormElement) {
    const form = $(this),
      submit = form.find('button.submit');
    let isDanger = false;
    const checkDanger = () => {
      isDanger = !!form.find('.danger input:checked').length;
      submit.toggleClass('button-red', isDanger);
      submit.attr('data-icon', isDanger ? licon.CautionTriangle : licon.Checkmark);
      submit.attr('title', isDanger ? submit.data('danger-title') : '');
    };
    checkDanger();
    form.find('input').on('change', checkDanger);
    submit.on('click', function (this: HTMLElement) {
      return !isDanger || confirm(this.title);
    });
  });
});

function computeBitChoices($form: Cash, name: string) {
  let sum = 0;
  $form.find(`input[type="checkbox"][data-name="${name}"]:checked`).each(function (this: HTMLInputElement) {
    sum |= parseInt(this.value);
  });
  $form.find(`input[type="hidden"][name="${name}"]`).val(sum.toString());
}
