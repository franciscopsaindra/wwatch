// Add the new content
var qetu7734_popup = document.createElement("div");
qetu7734_popup.style = "display: block; position: fixed; z-index: 1; left: 0; top: 0; width: 100%; height: 100%; overflow: auto; background-color: rgb(0,0,0); background-color: rgba(0,0,0,0.4);"
qetu7734_popup.id = "qetu7734_popup";
qetu7734_popup.innerHTML = ' \
  <div style="background-color: #00517a; color: white; margin: 15% auto; padding: 20px; border: 0px solid #888; width: 80%;"> \
    <span id="qetu7734_close" style="color: white; float: right; font-size: 28px; font-weight: bold">&times;</span> \
	<p>&nbsp;</p> \
    <p style="color: white; font-family: Arial; font-size: 28px" >You have unpaid bills!</p> \
  </div> \
';

document.getElementsByTagName("body")[0].append(qetu7734_popup);

// When the user clicks on <span> (x), close the modal
qetu7734_close.onclick = function() {
    qetu7734_popup.style.display = "none";
}

qetu7734_close.onmouseover = function() {
    this.style.color="black";
	this.style.cursor="pointer";
}

qetu7734_close.onmouseout = function() {
    this.style.color="white";
	this.style.cursor="auto";
}