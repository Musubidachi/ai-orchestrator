/*
 * Screenshots in notes. Any text box marked data-attach="<project id>" takes a pasted image
 * (Ctrl+V, from a snipping tool or the clipboard), a dropped image file, or one picked with the
 * "Add image" button placed after it. The image is saved in the project, where its agent can open it,
 * and the box gets a line such as "[image: .forge-images/20261011-141802-1.png]": the note stays
 * plain text, so wherever it already goes the picture goes too.
 *
 * Shared by the dashboard, the quick review, the design page and each project's report, so it marks
 * boxes as they appear rather than once at load: most of those boxes are drawn after the page starts.
 */
(function () {
  "use strict";

  var IMAGE = /^image\/(png|jpeg|gif|webp)$/;

  function imagesIn(list) {
    return Array.prototype.filter.call(list || [], function (file) { return IMAGE.test(file.type); });
  }

  function readAsDataUrl(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(reader.error); };
      reader.readAsDataURL(file);
    });
  }

  function insert(box, text) {
    var value = box.value;
    var at = typeof box.selectionStart === "number" ? box.selectionStart : value.length;
    var end = typeof box.selectionEnd === "number" ? box.selectionEnd : at;
    var before = value.slice(0, at);
    var spaced = (before && !/\s$/.test(before) ? " " : "") + text + " ";
    box.value = before + spaced + value.slice(end);
    var caret = (before + spaced).length;
    try { box.setSelectionRange(caret, caret); } catch (e) { /* not every input type has a caret */ }
    // The pages keep their own copy of a note from these events; a value set from script fires neither.
    box.dispatchEvent(new Event("input", {bubbles: true}));
    box.dispatchEvent(new Event("change", {bubbles: true}));
  }

  function say(box, message, failed) {
    var status = box.forgeAttachStatus;
    if (!status) return;
    status.textContent = message;
    status.className = "attach-status" + (failed ? " attach-failed" : "");
  }

  async function attach(box, files) {
    var images = imagesIn(files);
    if (!images.length) {
      say(box, "Only PNG, JPEG, GIF or WebP images can be added.", true);
      return;
    }
    for (var i = 0; i < images.length; i++) {
      say(box, "Adding image" + (images.length > 1 ? " " + (i + 1) + " of " + images.length : "") + "…");
      try {
        var data = await readAsDataUrl(images[i]);
        var response = await fetch("/api/jobs/attach", {
          method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({id: box.dataset.attach, data: data})
        });
        var answer = await response.json();
        if (answer.error || !answer.reference) {
          say(box, answer.error || "The image could not be added.", true);
          return;
        }
        insert(box, answer.reference);
      } catch (e) {
        say(box, "The image could not be added: " + e.message, true);
        return;
      }
    }
    say(box, images.length === 1 ? "Image added." : images.length + " images added.");
  }

  function boxOf(target) {
    return target && target.closest ? target.closest("[data-attach]") : null;
  }

  document.addEventListener("paste", function (event) {
    var box = boxOf(event.target);
    var images = box && event.clipboardData ? imagesIn(event.clipboardData.files) : [];
    if (!images.length) return;
    event.preventDefault();
    attach(box, images);
  }, true);

  document.addEventListener("dragover", function (event) {
    if (boxOf(event.target)) event.preventDefault();
  }, true);

  document.addEventListener("drop", function (event) {
    var box = boxOf(event.target);
    if (!box || !event.dataTransfer || !event.dataTransfer.files.length) return;
    event.preventDefault();
    attach(box, event.dataTransfer.files);
  }, true);

  function equip(box) {
    if (box.forgeAttachStatus || !box.dataset.attach) return;
    var picker = document.createElement("input");
    picker.type = "file";
    picker.accept = "image/png,image/jpeg,image/gif,image/webp";
    picker.multiple = true;
    picker.hidden = true;
    var button = document.createElement("button");
    button.type = "button";
    button.className = "attach-button";
    button.textContent = "Add image";
    button.title = "Add a screenshot to this note. You can also paste one (Ctrl+V) or drop a file on the box.";
    button.onclick = function () { picker.click(); };
    picker.onchange = function () {
      attach(box, picker.files);
      picker.value = "";
    };
    var status = document.createElement("span");
    status.className = "attach-status";
    status.setAttribute("role", "status");
    box.forgeAttachStatus = status;
    if (!box.placeholder || box.placeholder.indexOf("paste") < 0) {
      box.placeholder = (box.placeholder ? box.placeholder + " " : "") + "(you can paste a screenshot)";
    }
    box.insertAdjacentElement("afterend", status);
    box.insertAdjacentElement("afterend", button);
    box.insertAdjacentElement("afterend", picker);
  }

  function equipAll(root) {
    if (root.matches && root.matches("[data-attach]")) equip(root);
    if (root.querySelectorAll) root.querySelectorAll("[data-attach]").forEach(equip);
  }

  var style = document.createElement("style");
  style.textContent = ".attach-button{margin:.3rem .4rem 0 0;padding:.2rem .6rem;font-size:.8rem;cursor:pointer}"
    + ".attach-status{font-size:.8rem;opacity:.8}.attach-failed{color:#c0392b;opacity:1}"
    // A note box the page hides (a row not being sent back) takes its button with it.
    + "[data-attach][hidden]+input+.attach-button,[data-attach][hidden]+input+.attach-button+.attach-status{display:none}";
  document.head.appendChild(style);

  function start() {
    equipAll(document.body);
    new MutationObserver(function (changes) {
      changes.forEach(function (change) {
        change.addedNodes.forEach(function (node) { if (node.nodeType === 1) equipAll(node); });
        if (change.type === "attributes") equipAll(change.target);
      });
    }).observe(document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ["data-attach"]});
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
