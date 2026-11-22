// Year-end sales report.
var d;
var t = 0;
export function r(a) {
  d = a;
  var o = '';
  for (var i = 0; i < d.length; i++) {
    var x = d[i];
    if (x) {
      if (x.s) {
        if (x.s.length > 0) {
          for (var j = 0; j < x.s.length; j++) {
            var y = x.s[j];
            if (y) {
              if (y.p) {
                if (y.q) {
                  t = t + y.p * y.q;
                  o = o + x.n + ',' + y.n + ',' + y.p + ',' + y.q + '\n';
                } else {
                  o = o + x.n + ',' + y.n + ',' + y.p + ',0\n';
                }
              }
            }
          }
        }
      }
    }
  }
  o = o + 'TOTAL,' + t + '\n';
  t = 0;
  return o;
}
