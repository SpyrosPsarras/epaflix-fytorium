import { useState } from 'react';
import { BackHandler, Keyboard, Text, TextInput } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { Dialog, PaperProvider, Portal } from 'react-native-paper';
import { Stoppable } from '../shell/Stoppable';

function Form() {
  const [text, setText] = useState('');
  return (
    <>
      <TextInput testID="draft" value={text} onChangeText={setText} />
      <Portal>
        <Dialog visible><Dialog.Title>Discard changes?</Dialog.Title></Dialog>
      </Portal>
    </>
  );
}

const tree = (stop: React.ReactNode | null) => <PaperProvider><Stoppable stop={stop}><Form /></Stoppable></PaperProvider>;

test('the update stop covers the app and its open dialog without unmounting them, and the draft is there when it clears', async () => {
  const exit = jest.spyOn(BackHandler, 'exitApp').mockImplementation(() => undefined);
  const dismiss = jest.spyOn(Keyboard, 'dismiss');
  const view = await render(tree(null));
  await fireEvent.changeText(screen.getByTestId('draft'), 'Kept');
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  await view.rerender(tree(<Text>Update Fytorium</Text>));
  expect(dismiss).toHaveBeenCalledTimes(1);
  expect(screen.getByText('Update Fytorium')).toBeTruthy();
  expect(screen.queryByTestId('draft')).toBeNull();
  expect(screen.queryByText('Discard changes?')).toBeNull();
  expect(screen.getByText('Discard changes?', { includeHiddenElements: true })).toBeTruthy();
  expect(screen.getByTestId('draft', { includeHiddenElements: true }).props.value).toBe('Kept');
  await view.rerender(tree(null));
  expect(screen.getByTestId('draft').props.value).toBe('Kept');
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  exit.mockRestore();
  dismiss.mockRestore();
});
