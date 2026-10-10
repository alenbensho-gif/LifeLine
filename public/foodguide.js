// Food Guide page: general education only, not medical advice.
(function () {
  var F = {
    lentils: ["🫘", "Lentils / dal", "~6 mg iron per cup cooked"],
    spinach: ["🥬", "Spinach & greens", "Iron + folate"],
    beet: ["🟣", "Beetroot", "Folate; supports red cells"],
    dates: ["🌴", "Dates & raisins", "Iron + quick energy"],
    pom: ["🍎", "Pomegranate / apple", "Hydrating, vitamin C"],
    orange: ["🍊", "Orange, lemon, amla", "Vitamin C boosts iron absorption"],
    egg: ["🥚", "Eggs", "Protein, B12, iron"],
    meat: ["🍗", "Lean meat / chicken", "Easily absorbed heme iron"],
    liver: ["🥩", "Red meat / liver", "Richest heme iron (small portions)"],
    fish: ["🐟", "Fish", "Protein and B12"],
    chick: ["🧆", "Chickpeas / rajma", "~4–5 mg iron per cup cooked"],
    tofu: ["🧈", "Tofu / paneer", "Protein; tofu has iron"],
    seeds: ["🎃", "Pumpkin & sesame seeds", "Iron, zinc"],
    ragi: ["🌾", "Ragi / whole grains", "Iron and calcium"],
    coconut: ["🥥", "Coconut water", "Fluids and electrolytes"],
    milk: ["🥛", "Buttermilk / milk", "Fluids, protein, B12"],
    water: ["💧", "Water & ORS", "Replace lost fluid"],
    soup: ["🍲", "Warm soup / khichdi", "Gentle, easy to digest"],
    banana: ["🍌", "Banana", "Potassium and energy"]
  };
  var L = [
    { t: "Minor", a: "Under ~100 ml", d: "Nosebleed, small cut, a normal period", tone: "ok",
      msg: "Usually no medical care is needed. Drink fluids and eat regular balanced meals with some iron-rich foods.",
      foods: ["water", "coconut", "lentils", "spinach", "orange", "egg", "banana"],
      fluids: "Extra 1–2 glasses of water over the day.",
      tips: ["Pair iron foods with vitamin C (e.g. dal with lemon).", "Rest if you feel light-headed."],
      see: "Bleeding that won't stop after 10–15 minutes of pressure." },
    { t: "Mild", a: "~100–500 ml", d: "Blood donation, heavy period, minor injury", tone: "ok",
      msg: "Your body replaces fluid within 24 hours; red cells take weeks. Focus on fluids first, then iron, B12 and folate.",
      foods: ["water", "coconut", "milk", "soup", "lentils", "spinach", "dates", "beet", "egg", "meat", "orange", "ragi"],
      fluids: "Extra 500 ml–1 litre of fluids over the next 24 hours; avoid alcohol for 24 hours.",
      tips: ["Eat within an hour or two: a meal with protein and iron.", "Avoid tea/coffee with meals; they reduce iron absorption.", "Skip strenuous exercise for 24 hours.", "Continue iron-rich meals daily for 2–4 weeks."],
      see: "Dizziness on standing, fast heartbeat, fainting, or heavy bleeding that recurs." },
    { t: "Moderate", a: "~500 ml–1.5 L", d: "Significant injury, surgery, childbirth, heavy internal bleeding", tone: "warn",
      msg: "This needs medical assessment. Follow your care team's advice about fluids, transfusion and iron. The foods below support recovery once you are stable.",
      foods: ["soup", "water", "coconut", "milk", "egg", "liver", "meat", "fish", "lentils", "chick", "spinach", "beet", "dates", "seeds", "orange", "pom"],
      fluids: "Fluids as directed by your doctor (oral rehydration, or IV in hospital).",
      tips: ["Small, frequent, protein-rich meals if appetite is low.", "Doctors often check haemoglobin and ferritin; supplements may be prescribed.", "Add vitamin C fruit to each iron-rich meal.", "Recovery of red cells can take 4–8 weeks or more."],
      see: "Get assessed now if not already. Seek urgent help for pale/cold skin, confusion, breathlessness, chest pain or fainting." },
    { t: "Severe", a: "Over ~1.5 L", d: "Major trauma, haemorrhage", tone: "alert",
      msg: "🚨 This is a medical emergency. Call emergency services (India: 108 or 112) or go to the nearest hospital. Do not rely on food or drink. Do not eat or drink if surgery may be needed unless told to.",
      foods: [], fluids: "Nothing by mouth until a medical team advises.",
      tips: ["Apply firm direct pressure to the wound and keep the person lying down.", "Food plan comes later, from the hospital dietitian, once the person is stable."],
      see: "Right now." }
  ];

  var levels = document.getElementById("fgLevels"), out = document.getElementById("fgOut");
  if (!levels || !out) return;

  L.forEach(function (x, i) {
    var b = document.createElement("button");
    b.className = "fg-lv"; b.type = "button";
    b.innerHTML = '<span class="fg-drop">' + "🩸".repeat(i + 1) + '</span><b>' + x.t + '</b><span>' + x.a + '</span><br><span>' + x.d + '</span>';
    b.onclick = function () { show(i); };
    levels.appendChild(b);
  });

  function show(i) {
    var x = L[i];
    Array.prototype.forEach.call(levels.children, function (c, j) { c.setAttribute("aria-pressed", j === i); });
    var h = '<div class="fg-alert ' + (x.tone === "ok" ? "ok" : x.tone === "warn" ? "warn" : "") + '">' + x.msg + '</div>';
    if (x.foods.length) {
      h += '<div class="panel fg-panel"><h3>Recommended foods</h3><div class="fg-grid">' + x.foods.map(function (k) {
        var f = F[k];
        return '<div class="fg-food"><div class="fg-pic">' + f[0] + '</div><b>' + f[1] + '</b><small>' + f[2] + '</small></div>';
      }).join("") + '</div></div>';
    }
    h += '<div class="fg-two"><div class="panel fg-panel"><h3>💧 Fluids</h3><p>' + x.fluids + '</p></div><div class="panel fg-panel"><h3>⚠️ Get help if</h3><p>' + x.see + '</p></div></div>';
    h += '<div class="panel fg-panel"><h3>Tips</h3><ul>' + x.tips.map(function (t) { return "<li>" + t + "</li>"; }).join("") + '</ul></div>';
    out.innerHTML = h;
  }
  show(1);
})();
