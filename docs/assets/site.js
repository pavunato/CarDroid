// EN/VI switch: remembers the choice, otherwise follows the browser language.
(function () {
  var root = document.documentElement;
  var saved = null;
  try { saved = localStorage.getItem("cardroid-lang"); } catch (e) {}
  var lang = saved || ((navigator.language || "").toLowerCase().indexOf("vi") === 0 ? "vi" : "en");

  function apply(next) {
    lang = next;
    root.setAttribute("data-lang", next);
    root.setAttribute("lang", next);
    var title = document.querySelector('meta[name="title-' + next + '"]');
    if (title) document.title = title.content;
    document.querySelectorAll(".lang button").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.dataset.set === next));
    });
  }

  apply(lang);
  document.addEventListener("DOMContentLoaded", function () {
    apply(lang);
    document.querySelectorAll(".lang button").forEach(function (b) {
      b.addEventListener("click", function () {
        apply(b.dataset.set);
        try { localStorage.setItem("cardroid-lang", b.dataset.set); } catch (e) {}
      });
    });
  });
})();
