const button = document.getElementById("count");
let clicks = 0;
button.addEventListener("click", () => {
  button.textContent = `Clicked ${++clicks} times`;
});
