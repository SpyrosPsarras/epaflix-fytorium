import { useState } from 'react';
import { BackHandler, Text, TextInput } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { Stoppable } from '../shell/Stoppable';

function Form() {
  const [text, setText] = useState('');
  return <TextInput testID="draft" value={text} onChangeText={setText} />;
}

test('the update stop covers the app without unmounting it, and the draft is there when it clears', async () => {
  const exit = jest.spyOn(BackHandler, 'exitApp').mockImplementation(() => undefined);
  const view = await render(<Stoppable stop={null}><Form /></Stoppable>);
  await fireEvent.changeText(screen.getByTestId('draft'), 'Kept');
  await view.rerender(<Stoppable stop={<Text>Update Fytorium</Text>}><Form /></Stoppable>);
  expect(screen.getByText('Update Fytorium')).toBeTruthy();
  expect(screen.queryByTestId('draft')).toBeNull();
  expect(screen.getByTestId('draft', { includeHiddenElements: true }).props.value).toBe('Kept');
  await view.rerender(<Stoppable stop={null}><Form /></Stoppable>);
  expect(screen.getByTestId('draft').props.value).toBe('Kept');
  exit.mockRestore();
}, 20_000);
