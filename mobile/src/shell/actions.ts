import { CommonActions, StackActions, createNavigationContainerRef } from '@react-navigation/native';

export const navigation = createNavigationContainerRef();

export function stackOf(tab: string) {
  return navigation.getRootState()?.routes.find((route) => route.name === tab)?.state;
}

// A reselect (W6-P6) or a notification tap (W6-P16) closes the pages over a root; their questions ask first.
export function popToRoot(tab: string) {
  const stack = stackOf(tab);
  if (stack && (stack.routes?.length ?? 0) > 1) navigation.dispatch({ ...StackActions.popToTop(), target: stack.key });
}

let signal = 0;
// W6-P6 at a root, and W6-P15: the root scrolls to the top and saves that, and the Plants grid can drop its search.
export function rootToTop(tab: string, clearSearch = false) {
  const stack = stackOf(tab);
  const root = stack?.routes?.[0];
  if (!stack?.key || !root?.key) return;
  signal += 1;
  navigation.dispatch({ ...CommonActions.setParams({ top: signal, ...(clearSearch ? { clear: signal } : {}) }), source: root.key, target: stack.key });
}

// W6-P6: a reselect closes the pages over the root or, at the root, goes to the top.
export function reselect(tab: string) {
  if ((stackOf(tab)?.routes?.length ?? 1) > 1) popToRoot(tab);
  else rootToTop(tab);
}
