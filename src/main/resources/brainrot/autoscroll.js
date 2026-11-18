(function () {
  if (window.__brainrotAutoScroll) return;
  window.__brainrotAutoScroll = true;

  let video = null;
  let src = '';
  let last = 0;
  let finished = 0;
  let endedSeen = false;

  function next() {
    /*NEXT*/
  }

  // Used when the Arrow Down from the host did not move the feed on.
  function scrollOn(v) {
    for (let e = v.parentElement; e; e = e.parentElement) {
      if (e.scrollHeight > e.clientHeight + 10 && getComputedStyle(e).overflowY !== 'visible') {
        e.scrollBy({top: e.clientHeight, behavior: 'smooth'});
        return;
      }
    }
    window.scrollBy({top: window.innerHeight, behavior: 'smooth'});
  }

  setInterval(function () {
    const v = Array.from(document.querySelectorAll('video')).find(function (x) {
      return (!x.paused || x.ended) && x.getBoundingClientRect().height > 0;
    });
    if (!v) return;
    if (v !== video || v.currentSrc !== src) {
      video = v;
      src = v.currentSrc;
      last = 0;
      finished = 0;
      endedSeen = false;
    }
    const t = v.currentTime;
    // Feeds loop their clips, so "finished" is the playhead jumping from the end back to the start.
    const looped = t + 1 < last && last > v.duration - 1;
    const ended = v.ended && !endedSeen;
    endedSeen = v.ended;
    if (looped || ended) {
      finished++;
      if (finished === 1) next(); else scrollOn(v);
    }
    last = t;
  }, 250);
})();
